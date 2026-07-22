package com.recon.dash.ui.telemetry

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.recon.dash.dash.TelemetryBus
import com.recon.dash.dash.TelemetryPacket
import com.recon.dash.util.DebugLog
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

data class ManualEntry(
    val timestampMs: Long,
    val field: String,
    val value: String,
)

data class TelemetryLabState(
    val packets: List<TelemetryPacket> = emptyList(),
    val manualEntries: List<ManualEntry> = emptyList(),
    val isRecording: Boolean = false,
    val packetCount: Int = 0,
    val uniqueSubs: Set<String> = emptySet(),
)

@HiltViewModel
class TelemetryLabViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
) : ViewModel() {

    companion object {
        private const val TAG = "TelemetryLab"
        private const val MAX_DISPLAY = 200
    }

    private val _state = MutableStateFlow(TelemetryLabState())
    val state = _state.asStateFlow()

    private val allPackets = mutableListOf<TelemetryPacket>()
    private val allEntries = mutableListOf<ManualEntry>()
    private val subs = mutableSetOf<String>()

    init {
        viewModelScope.launch {
            TelemetryBus.packets.collect { packet -> onPacket(packet) }
        }
    }

    fun startRecording() {
        allPackets.clear()
        allEntries.clear()
        subs.clear()
        _state.value = TelemetryLabState(isRecording = true)
    }

    fun stopRecording() {
        _state.value = _state.value.copy(isRecording = false)
    }

    fun onPacket(packet: TelemetryPacket) {
        if (!_state.value.isRecording) return
        allPackets.add(packet)
        subs.add(packet.label)
        val display = if (allPackets.size > MAX_DISPLAY) allPackets.takeLast(MAX_DISPLAY) else allPackets.toList()
        _state.value = _state.value.copy(
            packets = display,
            packetCount = allPackets.size,
            uniqueSubs = subs.toSet(),
        )
    }

    fun logManualEntry(field: String, value: String) {
        val entry = ManualEntry(
            timestampMs = System.currentTimeMillis(),
            field = field,
            value = value,
        )
        allEntries.add(entry)
        _state.value = _state.value.copy(manualEntries = allEntries.toList())
        DebugLog.i(TAG) { "MANUAL: $field = $value @ ${entry.timestampMs}" }
    }

    fun exportSession(): String? {
        if (allPackets.isEmpty() && allEntries.isEmpty()) return null

        return viewModelScope.let {
            val dateStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val dir = File(context.getExternalFilesDir(null), "telemetry_captures")
            dir.mkdirs()
            val file = File(dir, "telemetry_$dateStr.csv")

            val sb = StringBuilder()
            sb.appendLine("timestamp_ms,source,type,sub,length,hex_data")

            for (pkt in allPackets) {
                val hex = pkt.decrypted?.joinToString("") { "%02X".format(it) } ?: pkt.rawHex.replace(" ", "")
                sb.appendLine("${pkt.timestampMs},BIKE,${pkt.typeHex},${pkt.subHex},${pkt.decrypted?.size ?: pkt.raw.size},$hex")
            }

            sb.appendLine()
            sb.appendLine("timestamp_ms,source,field,value")
            for (entry in allEntries) {
                sb.appendLine("${entry.timestampMs},MANUAL,${entry.field},${entry.value}")
            }

            file.writeText(sb.toString())
            DebugLog.i(TAG) { "Exported ${allPackets.size} packets + ${allEntries.size} entries to ${file.absolutePath}" }
            file.absolutePath
        }
    }
}
