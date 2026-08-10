package com.recon.dash.obd

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.recon.dash.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Live telemetry from a Bluetooth ELM327 OBD-II dongle (classic SPP). Connects to a PAIRED dongle,
 * runs the ELM327 init sequence, then polls the standard PIDs and parses `41 xx ..` responses into
 * [EngineState]. The dash puts NO engine data on WiFi (docs/OBD_TELEMETRY_FINDINGS.md), so this
 * dongle is the only real source.
 *
 * Fine-grained [status] lets the UI prompt correctly: BT_OFF (ask to enable Bluetooth), NO_DEVICE
 * (no paired OBD dongle — ask to connect the module), CONNECTING, CONNECTED, ERROR.
 */
class Elm327Source(private val context: Context) : TelemetrySource {

    private val _status = MutableStateFlow(TelemetryStatus.DISCONNECTED)
    override val status = _status.asStateFlow()
    private val _state = MutableStateFlow(EngineState.EMPTY)
    override val state = _state.asStateFlow()

    /** Extra detail beyond TelemetryStatus so the UI can show the right prompt. */
    enum class Reason { NONE, BT_OFF, NO_DEVICE, NO_PERMISSION, CONNECT_FAILED }
    private val _reason = MutableStateFlow(Reason.NONE)
    val reason = _reason.asStateFlow()

    @Volatile private var running = false
    @Volatile private var socket: BluetoothSocket? = null

    override suspend fun start() {
        if (running) return
        running = true
        _status.value = TelemetryStatus.CONNECTING; _reason.value = Reason.NONE

        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE)
            as? android.bluetooth.BluetoothManager)?.adapter
        if (adapter == null) { fail(Reason.NO_DEVICE); return }
        if (!hasConnectPermission()) { fail(Reason.NO_PERMISSION); return }
        if (!adapter.isEnabled) { fail(Reason.BT_OFF); return }

        val device = findObdDevice(adapter)
        if (device == null) { fail(Reason.NO_DEVICE); return }

        coroutineScope {
            withContext(Dispatchers.IO) {
                try {
                    connectAndPoll(device)
                } catch (e: SecurityException) {
                    DebugLog.w(TAG) { "BT permission lost mid-connect: ${e.message}" }; fail(Reason.NO_PERMISSION)
                } catch (e: Exception) {
                    DebugLog.w(TAG) { "OBD connect/poll failed: ${e.message}" }
                    if (running) fail(Reason.CONNECT_FAILED)
                } finally {
                    runCatching { socket?.close() }; socket = null
                }
            }
        }
    }

    override fun stop() {
        running = false
        runCatching { socket?.close() }; socket = null
        _status.value = TelemetryStatus.DISCONNECTED
    }

    private fun fail(r: Reason) {
        _reason.value = r
        _status.value = TelemetryStatus.ERROR
        running = false
    }

    private fun hasConnectPermission(): Boolean =
        // BLUETOOTH_CONNECT is only required at runtime on API 31+.
        android.os.Build.VERSION.SDK_INT < 31 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    /** First paired device whose name looks like an OBD/ELM327 dongle. */
    private fun findObdDevice(adapter: BluetoothAdapter): BluetoothDevice? = runCatching {
        adapter.bondedDevices?.firstOrNull { d ->
            val n = (d.name ?: "").uppercase()
            n.contains("OBD") || n.contains("ELM") || n.contains("VLINK") || n.contains("VEEPEAK") ||
                n.contains("VGATE") || n.contains("ICAR") || n.contains("KONNWEI")
        }
    }.getOrNull()

    private suspend fun connectAndPoll(device: BluetoothDevice) {
        val sock = device.createRfcommSocketToServiceRecord(SPP_UUID)
        socket = sock
        sock.connect()   // blocks; throws on failure
        val out = sock.outputStream
        val inp = sock.inputStream
        DebugLog.i(TAG) { "OBD connected: ${runCatching { device.name }.getOrNull()}" }

        // ELM327 init: reset, echo off, linefeeds off, headers off, auto protocol.
        for (cmd in listOf("ATZ", "ATE0", "ATL0", "ATH0", "ATSP0")) {
            sendCmd(out, inp, cmd); delay(120)
        }
        _status.value = TelemetryStatus.CONNECTED; _reason.value = Reason.NONE

        while (running && coroutineContext.isActive) {
            val rpm = queryRpm(out, inp)
            val speed = querySpeed(out, inp)
            val coolant = queryCoolant(out, inp)
            val throttle = queryThrottle(out, inp)
            _state.value = EngineState(
                rpm = rpm, speedKmh = speed, coolantC = coolant, throttlePct = throttle,
                timestampMs = System.currentTimeMillis(),
            )
            delay(POLL_INTERVAL_MS)
        }
    }

    // ── PID queries — send "01XX", read "41 XX ..", parse per SAE J1979 ──
    private fun queryRpm(o: OutputStream, i: InputStream): Int? =
        bytes(sendCmd(o, i, "010C"), 0x0C)?.let { if (it.size >= 2) ((it[0] * 256 + it[1]) / 4) else null }

    private fun querySpeed(o: OutputStream, i: InputStream): Int? =
        bytes(sendCmd(o, i, "010D"), 0x0D)?.firstOrNull()

    private fun queryCoolant(o: OutputStream, i: InputStream): Int? =
        bytes(sendCmd(o, i, "0105"), 0x05)?.firstOrNull()?.let { it - 40 }   // A-40 °C

    private fun queryThrottle(o: OutputStream, i: InputStream): Int? =
        bytes(sendCmd(o, i, "0111"), 0x11)?.firstOrNull()?.let { it * 100 / 255 }  // A*100/255 %

    /** Send an ELM command, read until the '>' prompt, return the raw ASCII reply. */
    private fun sendCmd(out: OutputStream, inp: InputStream, cmd: String): String {
        return try {
            out.write((cmd + "\r").toByteArray()); out.flush()
            val sb = StringBuilder()
            val buf = ByteArray(64)
            val deadline = System.currentTimeMillis() + CMD_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                if (inp.available() > 0) {
                    val n = inp.read(buf)
                    if (n > 0) {
                        sb.append(String(buf, 0, n))
                        if (sb.contains('>')) break
                    }
                } else Thread.sleep(10)
            }
            sb.toString()
        } catch (e: Exception) { DebugLog.w(TAG) { "cmd $cmd failed: ${e.message}" }; "" }
    }

    /**
     * Extract the data bytes from a "41 <pid> <A> <B> ..." reply. Returns the bytes AFTER the
     * mode+pid header, or null if the reply doesn't contain a valid 41-<pid> frame (NO DATA, etc.).
     */
    private fun bytes(reply: String, pid: Int): List<Int>? {
        val hex = reply.replace(Regex("[^0-9A-Fa-f ]"), " ").trim().split(Regex("\\s+"))
            .mapNotNull { it.toIntOrNull(16) }
        val idx = (0 until hex.size - 1).firstOrNull { hex[it] == 0x41 && hex[it + 1] == pid } ?: return null
        return hex.drop(idx + 2).takeIf { it.isNotEmpty() }
    }

    companion object {
        private const val TAG = "Elm327Source"
        private val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val POLL_INTERVAL_MS = 250L    // ~4 Hz full cycle
        private const val CMD_TIMEOUT_MS = 400L
    }
}
