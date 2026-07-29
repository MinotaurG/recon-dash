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
        val lat = loc.lat
        val lng = loc.lng
        val suggested = when {
            lat in 15.8..19.9 && lng in 77.0..81.0 -> "telangana"
            lat in 11.5..15.8 && lng in 74.0..78.5 -> "karnataka"
            lat in 8.0..13.0 && lng in 76.0..80.5 -> "tamil_nadu"
            lat in 8.0..13.0 && lng in 74.5..77.5 -> "kerala"
            lat in 13.0..19.5 && lng in 76.5..84.5 -> "andhra_pradesh"
            lat in 15.5..22.0 && lng in 72.5..81.0 -> "maharashtra"
            lat in 14.5..15.8 && lng in 73.5..74.5 -> "goa"
            lat in 20.0..24.5 && lng in 68.0..74.5 -> "gujarat"
            lat in 23.0..30.0 && lng in 69.5..78.5 -> "rajasthan"
            lat in 28.0..29.0 && lng in 76.5..77.5 -> "delhi_ncr"
            lat in 23.5..31.0 && lng in 77.0..84.5 -> "uttar_pradesh"
            lat in 21.0..26.5 && lng in 74.0..82.5 -> "madhya_pradesh"
            lat in 29.5..33.0 && lng in 73.5..77.0 -> "punjab"
            lat in 27.5..31.0 && lng in 74.5..77.5 -> "haryana"
            lat in 30.5..33.5 && lng in 75.5..79.0 -> "himachal"
            lat in 28.5..31.5 && lng in 77.5..81.0 -> "uttarakhand"
            lat in 32.0..37.0 && lng in 73.5..80.0 -> "jammu_kashmir"
            lat in 32.0..36.0 && lng in 75.5..78.5 -> "ladakh"
            lat in 21.5..27.0 && lng in 85.5..89.0 -> "west_bengal"
            lat in 17.5..22.5 && lng in 81.0..87.5 -> "odisha"
            lat in 24.0..27.5 && lng in 83.0..88.5 -> "bihar"
            lat in 21.5..25.5 && lng in 83.0..87.5 -> "jharkhand"
            lat in 17.5..24.0 && lng in 80.0..84.5 -> "chhattisgarh"
            lat in 24.0..28.0 && lng in 89.5..96.0 -> "assam"
            lat in 27.0..28.5 && lng in 88.0..89.0 -> "sikkim"
            lat in 25.0..26.5 && lng in 89.5..92.5 -> "meghalaya"
            lat in 26.5..29.5 && lng in 91.5..97.5 -> "arunachal"
            else -> null
        }
        _suggestedRegionId.value = suggested
    }
}
