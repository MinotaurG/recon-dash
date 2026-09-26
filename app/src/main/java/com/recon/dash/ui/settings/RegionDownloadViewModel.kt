package com.recon.dash.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.recon.dash.data.RegionManager
import com.recon.dash.data.RoutingManifest
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RegionDownloadViewModel @Inject constructor(
    private val regionManager: RegionManager,
) : ViewModel() {

    val downloadState = regionManager.downloadState

    // The manifest (single India map + single India routing entry). Fetched from R2 on open.
    private val _manifest = MutableStateFlow<RoutingManifest?>(null)
    val manifest = _manifest.asStateFlow()

    private val _installedSizeMb = MutableStateFlow(regionManager.installedSizeMb())
    val installedSizeMb = _installedSizeMb.asStateFlow()

    // All-India routing extract (one download).
    private val _routingInstalled = MutableStateFlow(regionManager.isRoutingInstalled())
    val routingInstalled = _routingInstalled.asStateFlow()
    private val _routingUpdateAvailable = MutableStateFlow(false)
    val routingUpdateAvailable = _routingUpdateAvailable.asStateFlow()
    val routingSizeMb: Int get() = _manifest.value?.routing?.sizeMb ?: RegionManager.INDIA_ROUTING_SIZE_MB

    // All-India display map (one download) — separate from routing.
    private val _indiaMapInstalled = MutableStateFlow(regionManager.isIndiaMapInstalled())
    val indiaMapInstalled = _indiaMapInstalled.asStateFlow()
    val indiaMapSizeMb: Int get() = _manifest.value?.map?.sizeMb ?: RegionManager.INDIA_MAP_SIZE_MB
    private val _mapUpdateAvailable = MutableStateFlow(false)
    val mapUpdateAvailable = _mapUpdateAvailable.asStateFlow()

    init {
        viewModelScope.launch {
            _manifest.value = regionManager.manifest()
            refresh()
        }
    }

    fun downloadIndiaRouting() = viewModelScope.launch { regionManager.downloadIndiaRouting(); refresh() }
    fun clearRouting() { regionManager.clearRouting(); refresh() }

    fun downloadIndiaMap() = viewModelScope.launch { regionManager.downloadIndiaMap(); refresh() }
    fun clearIndiaMap() { regionManager.clearIndiaMap(); refresh() }

    private fun refresh() {
        _installedSizeMb.value = regionManager.installedSizeMb()
        _routingInstalled.value = regionManager.isRoutingInstalled()
        _routingUpdateAvailable.value = regionManager.routingUpdateAvailable()
        _indiaMapInstalled.value = regionManager.isIndiaMapInstalled()
        _mapUpdateAvailable.value = regionManager.mapUpdateAvailable()
    }
}
