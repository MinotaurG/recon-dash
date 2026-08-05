package com.recon.dash.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.recon.dash.data.RegionManager
import com.recon.dash.data.RoutingManifest
import com.recon.dash.data.RoutingPack
import com.recon.dash.data.RoutingZone
import com.recon.dash.util.LocationHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RegionDownloadViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val regionManager: RegionManager,
) : ViewModel() {

    val downloadState = regionManager.downloadState

    // The routing manifest (zones -> state packs). Fetched from R2 on open.
    private val _manifest = MutableStateFlow<RoutingManifest?>(null)
    val manifest = _manifest.asStateFlow()

    // Set of installed pack ids (packs stack, so this is a SET, not a single id).
    private val _installedPacks = MutableStateFlow(regionManager.installedPackIds())
    val installedPacks = _installedPacks.asStateFlow()

    // The state pack the rider is currently in (for a "download this" nudge), or null.
    private val _suggestedStateId = MutableStateFlow<String?>(null)
    val suggestedStateId = _suggestedStateId.asStateFlow()

    private val _installedSizeMb = MutableStateFlow(regionManager.installedSizeMb())
    val installedSizeMb = _installedSizeMb.asStateFlow()

    // All-India display map (one download) — separate from routing packs.
    private val _indiaMapInstalled = MutableStateFlow(regionManager.isIndiaMapInstalled())
    val indiaMapInstalled = _indiaMapInstalled.asStateFlow()
    // Size from the manifest when available, else the baked-in fallback.
    val indiaMapSizeMb: Int get() = _manifest.value?.map?.sizeMb ?: RegionManager.INDIA_MAP_SIZE_MB

    // True when a newer display-map version exists on R2 than what's installed → show "Update".
    private val _mapUpdateAvailable = MutableStateFlow(false)
    val mapUpdateAvailable = _mapUpdateAvailable.asStateFlow()

    init {
        viewModelScope.launch {
            _manifest.value = regionManager.manifest()
            _mapUpdateAvailable.value = regionManager.mapUpdateAvailable()
            detectSuggested()
        }
    }

    fun downloadPack(pack: RoutingPack) = viewModelScope.launch {
        regionManager.downloadPack(pack); refresh()
    }

    fun downloadZone(zone: RoutingZone) = viewModelScope.launch {
        regionManager.downloadZone(zone); refresh()
    }

    /** Download the whole country (base + every state). */
    fun downloadAll() = viewModelScope.launch {
        regionManager.downloadAll(); refresh()
    }

    fun clearGraph() { regionManager.clearGraph(); refresh() }

    fun downloadIndiaMap() = viewModelScope.launch { regionManager.downloadIndiaMap(); refresh() }
    fun clearIndiaMap() { regionManager.clearIndiaMap(); refresh() }

    private fun detectSuggested() {
        val loc = LocationHelper.getLastKnown(context) ?: return
        val here = regionManager.stateIdForLocation(loc.lat, loc.lng)
        _suggestedStateId.value = here?.takeIf { !regionManager.isPackInstalled(it) }
    }

    private fun refresh() {
        _installedPacks.value = regionManager.installedPackIds()
        _installedSizeMb.value = regionManager.installedSizeMb()
        _indiaMapInstalled.value = regionManager.isIndiaMapInstalled()
        _mapUpdateAvailable.value = regionManager.mapUpdateAvailable()
        detectSuggested()
    }
}
