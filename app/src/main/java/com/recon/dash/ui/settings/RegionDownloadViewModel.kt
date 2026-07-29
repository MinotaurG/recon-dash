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

    val regions: List<Region> get() = regionManager.availableRegions

    init {
        detectSuggestedRegion()
    }

    fun download(region: Region) {
        if (region.graphUrl.isBlank()) {
            return
        }
        viewModelScope.launch {
            regionManager.downloadRegion(region, region.graphUrl)
            _installed.value = regionManager.isGraphInstalled()
            _installedRegionId.value = regionManager.installedRegionId()
        }
    }

    fun clearGraph() {
        regionManager.clearGraph()
        _installed.value = false
        _installedRegionId.value = null
    }

    private fun detectSuggestedRegion() {
        val loc = LocationHelper.getLastKnown(context) ?: return
        // Point-in-polygon via RegionManager/RegionGeocoder — single source of truth (was a
        // duplicate bounding-box table here that disagreed with the router's).
        _suggestedRegionId.value = regionManager.regionForLocation(loc.lat, loc.lng)?.id
    }
}
