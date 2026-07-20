package com.recon.dash.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import com.recon.dash.dash.DashConfig
import com.recon.dash.dash.nav.Router
import com.recon.dash.dash.nav.VoiceManager
import com.recon.dash.dash.nav.VoiceMode
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val config: DashConfig,
) : ViewModel() {

    private val _ssid = MutableStateFlow(config.ssid)
    val ssid = _ssid.asStateFlow()

    private val voiceManager = VoiceManager.get(context)
    private val _voiceMode = MutableStateFlow(voiceManager.mode.value.name)
    val voiceMode = _voiceMode.asStateFlow()

    private val router = Router(context)
    val hasGraph: Boolean = router.graphExists()

    val tileCacheSize: String = computeTileCacheSize()

    fun forgetDash() {
        config.forgetDash()
        _ssid.value = ""
    }

    fun cycleVoiceMode() {
        val modes = VoiceMode.entries
        val current = voiceManager.mode.value
        val next = modes[(current.ordinal + 1) % modes.size]
        voiceManager.setMode(next)
        _voiceMode.value = next.name
    }

    private fun computeTileCacheSize(): String {
        val dir = File(context.cacheDir, "tiles_gmaps")
        if (!dir.exists()) return "Empty"
        val bytes = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        return when {
            bytes > 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
            bytes > 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
            bytes > 0 -> "%.0f KB".format(bytes / 1_000.0)
            else -> "Empty"
        }
    }
}
