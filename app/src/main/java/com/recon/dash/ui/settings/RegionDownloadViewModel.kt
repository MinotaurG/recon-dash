package com.recon.dash.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.recon.dash.data.DownloadState
import com.recon.dash.data.Region
import com.recon.dash.data.RegionManager
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

    private val _installed = MutableStateFlow(regionManager.isGraphInstalled())
    val installed = _installed.asStateFlow()

    // WHICH region is installed (only one graph fits at a time). Used for per-region "Installed"
    // status — the old code marked every region installed once ANY graph was present.
    private val _installedRegionId = MutableStateFlow(regionManager.installedRegionId())
    val installedRegionId = _installedRegionId.asStateFlow()

    private val _suggestedRegionId = MutableStateFlow<String?>(null)
    val suggestedRegionId = _suggestedRegionId.asStateFlow()

    // On-disk size of the installed maps (MB), for the "free up space" UI.
    private val _installedSizeMb = MutableStateFlow(regionManager.installedSizeMb())
    val installedSizeMb = _installedSizeMb.asStateFlow()

    val regions: List<Region> get() = regionManager.availableRegions

    /** Display name of the installed bundle, or null. */
    val installedRegionName: String?
        get() = _installedRegionId.value?.let { id -> regions.firstOrNull { it.id == id }?.name }

    init {
        detectSuggestedRegion()
    }

    fun download(region: Region) {
        if (region.graphUrl.isBlank()) {
            return
        }
        viewModelScope.launch {
            regionManager.downloadRegion(region, region.graphUrl)
            refresh()   // recomputes installed + clears the now-installed region's "Suggested" tag
        }
    }

    fun clearGraph() {
        regionManager.clearGraph()
        refresh()
    }

    private fun detectSuggestedRegion() {
        val loc = LocationHelper.getLastKnown(context) ?: return
        // "Suggested" = the bundle for the rider's location that is NOT already installed. Point-in
        // -polygon via RegionManager/RegionGeocoder (single source of truth). Recomputed via
        // refresh() after a download/clear so the tag clears once the region becomes installed
        // (was set once at init and never cleared -> the tag persisted after downloading).
        val here = regionManager.regionForLocation(loc.lat, loc.lng)?.id
        _suggestedRegionId.value = if (here != null && here != regionManager.installedRegionId()) here else null
    }

    /** Recompute installed + suggested state (call after download/clear so the UI reflects reality). */
    private fun refresh() {
        _installed.value = regionManager.isGraphInstalled()
        _installedRegionId.value = regionManager.installedRegionId()
        _installedSizeMb.value = regionManager.installedSizeMb()
        detectSuggestedRegion()
    }
}
