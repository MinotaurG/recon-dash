package com.recon.dash.obd

import android.content.Context
import com.recon.dash.util.DebugLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope

/**
 * Replays a real Car Scanner ride (shipped as `assets/obd_replay.csv`, decoded from a .brc) as a
 * live [TelemetrySource], honoring the original inter-sample timing. Lets the cyberpunk cluster run
 * on REAL engine data (RPM 0–6488, speed 0–122, coolant 39–96°C from an actual ride) with no bike
 * or dongle attached — so the UI is built/tuned against truth, then swapped to [Elm327Source] live.
 *
 * CSV columns: t_sec,rpm,speed,coolant,accel,fuel (blank = PID absent at that sample).
 */
class BrcReplaySource(
    private val context: Context,
    private val loop: Boolean = true,
    private val speedMultiplier: Double = 1.0,   // >1 replays faster (dev), 1.0 = real time
) : TelemetrySource {

    private val _status = MutableStateFlow(TelemetryStatus.DISCONNECTED)
    override val status = _status.asStateFlow()
    private val _state = MutableStateFlow(EngineState.EMPTY)
    override val state = _state.asStateFlow()

    private data class Sample(
        val tSec: Double, val rpm: Int?, val speed: Int?, val coolant: Int?,
        val accel: Double?, val fuel: Double?,
    )

    @Volatile private var running = false

    override suspend fun start() {
        if (running) return
        running = true
        _status.value = TelemetryStatus.CONNECTING
        val samples = runCatching { load() }.getOrElse {
            DebugLog.e(TAG, { "Replay load failed: ${it.message}" }, it)
            _status.value = TelemetryStatus.ERROR
            running = false
            return
        }
        if (samples.isEmpty()) { _status.value = TelemetryStatus.ERROR; running = false; return }
        _status.value = TelemetryStatus.CONNECTED
        DebugLog.i(TAG) { "Replay start: ${samples.size} samples, loop=$loop x$speedMultiplier" }
        coroutineScope {
            do {
                var prevT = samples.first().tSec
                for (s in samples) {
                    if (!running || !isActive) return@coroutineScope
                    val gapSec = (s.tSec - prevT).coerceAtLeast(0.0)
                    prevT = s.tSec
                    val waitMs = (gapSec * 1000.0 / speedMultiplier).toLong().coerceIn(0, 2000)
                    if (waitMs > 0) delay(waitMs)
                    _state.value = EngineState(
                        rpm = s.rpm, speedKmh = s.speed, coolantC = s.coolant,
                        accelMs2 = s.accel, fuelInstL100 = s.fuel,
                        timestampMs = System.currentTimeMillis(),
                    )
                }
            } while (loop && running && isActive)
        }
    }

    override fun stop() {
        running = false
        _status.value = TelemetryStatus.DISCONNECTED
    }

    private fun load(): List<Sample> {
        val out = ArrayList<Sample>()
        context.assets.open(ASSET).bufferedReader().useLines { lines ->
            lines.drop(1).forEach { line ->
                val c = line.split(',')
                if (c.size < 6) return@forEach
                fun d(i: Int) = c[i].trim().toDoubleOrNull()
                val t = d(0) ?: return@forEach
                out += Sample(
                    tSec = t,
                    rpm = d(1)?.toInt(), speed = d(2)?.toInt(), coolant = d(3)?.toInt(),
                    accel = d(4), fuel = d(5),
                )
            }
        }
        return out
    }

    companion object {
        private const val TAG = "BrcReplaySource"
        private const val ASSET = "obd_replay.csv"
    }
}
