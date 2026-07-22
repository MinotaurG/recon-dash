package com.recon.dash.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.recon.dash.dash.DashConfig
import com.recon.dash.dash.nav.Router
import com.recon.dash.dash.nav.VoiceManager
import com.recon.dash.dash.nav.VoiceMode
import com.recon.dash.data.FavoritePlace
import com.recon.dash.data.FavoriteRepository
import com.recon.dash.data.FavoriteSlot
import com.recon.dash.data.CUSTOM_SLOTS
import com.recon.dash.ui.theme.ThemeMode
import com.recon.dash.ui.theme.ThemeState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.io.File
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val config: DashConfig,
    private val favoriteRepo: FavoriteRepository,
) : ViewModel() {

    private val _ssid = MutableStateFlow(config.ssid)
    val ssid = _ssid.asStateFlow()

    private val voiceManager = VoiceManager.get(context)
    private val _voiceMode = MutableStateFlow(voiceManager.mode.value.name)
    val voiceMode = _voiceMode.asStateFlow()

    private val _musicApp = MutableStateFlow(config.musicApp)
    val musicApp = _musicApp.asStateFlow()

    private val _visibleSlots = MutableStateFlow(config.visibleCustomSlots)
    val visibleSlots = _visibleSlots.asStateFlow()

    private val _themeMode = MutableStateFlow(config.themeMode)
    val themeMode = _themeMode.asStateFlow()

    private val _speedAlert = MutableStateFlow(config.speedAlertKmh)
    val speedAlert = _speedAlert.asStateFlow()

    private val _projectWhenIdle = MutableStateFlow(config.projectWhenIdle)
    val projectWhenIdle = _projectWhenIdle.asStateFlow()

    fun toggleProjectWhenIdle() {
        val next = !_projectWhenIdle.value
        config.projectWhenIdle = next
        _projectWhenIdle.value = next
    }

    val customFavorites = favoriteRepo.observeAll()
        .map { list ->
            list.filter { it.slot != FavoriteSlot.HOME && it.slot != FavoriteSlot.OFFICE }
                .associateBy { it.slot }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val router = Router(context)
    val hasGraph: Boolean = router.graphExists()

    val tileCacheSize: String = computeTileCacheSize()

    private val musicApps = listOf(
        "",
        "com.spotify.music",
        "com.google.android.apps.youtube.music",
        "com.amazon.mp3",
    )

    private val speedAlertOptions = listOf(0, 60, 80, 100, 120)

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

    fun cycleMusicApp() {
        val current = _musicApp.value
        val idx = musicApps.indexOf(current)
        val next = musicApps[(idx + 1) % musicApps.size]
        config.musicApp = next
        _musicApp.value = next
    }

    fun cycleTheme() {
        val modes = ThemeMode.entries
        val current = ThemeMode.valueOf(_themeMode.value)
        val next = modes[(current.ordinal + 1) % modes.size]
        config.themeMode = next.name
        _themeMode.value = next.name
        ThemeState.mode = next
    }

    fun cycleSpeedAlert() {
        val current = _speedAlert.value
        val idx = speedAlertOptions.indexOf(current)
        val next = speedAlertOptions[((if (idx < 0) 0 else idx) + 1) % speedAlertOptions.size]
        config.speedAlertKmh = next
        _speedAlert.value = next
    }

    fun toggleSlotVisibility(slot: FavoriteSlot) {
        val current = _visibleSlots.value.toMutableSet()
        if (slot.name in current) current.remove(slot.name)
        else current.add(slot.name)
        config.visibleCustomSlots = current
        _visibleSlots.value = current
    }

    private fun computeTileCacheSize(): String {
        var totalBytes = 0L
        val pmtilesDir = File(context.filesDir, "pmtiles")
        if (pmtilesDir.exists()) {
            totalBytes += pmtilesDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }
        val cacheDir = File(context.cacheDir, "tiles_gmaps")
        if (cacheDir.exists()) {
            totalBytes += cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }
        return when {
            totalBytes > 1_000_000_000 -> "%.1f GB".format(totalBytes / 1_000_000_000.0)
            totalBytes > 1_000_000 -> "%.1f MB".format(totalBytes / 1_000_000.0)
            totalBytes > 0 -> "%.0f KB".format(totalBytes / 1_000.0)
            else -> "Empty"
        }
    }
}
