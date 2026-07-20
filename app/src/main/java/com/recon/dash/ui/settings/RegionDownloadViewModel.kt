package com.recon.dash.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.recon.dash.data.DownloadState
import com.recon.dash.data.Region
import com.recon.dash.data.RegionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RegionDownloadViewModel @Inject constructor(
    private val regionManager: RegionManager,
) : ViewModel() {

    private val _regions = MutableStateFlow(regionManager.availableRegions)
    val regions = _regions.asStateFlow()

    val downloadState = regionManager.downloadState

    private val _installed = MutableStateFlow(regionManager.isGraphInstalled())
    val installed = _installed.asStateFlow()

    fun download(region: Region) {
        if (region.graphUrl.isBlank()) {
            // URLs will be populated once the build server produces graph files.
            // For now, this is a placeholder that shows the UI flow works.
            viewModelScope.launch {
                val placeholder = region.copy(
                    graphUrl = "https://example.com/graphs/${region.id}.zip"
                )
                regionManager.downloadRegion(placeholder, placeholder.graphUrl)
                _installed.value = regionManager.isGraphInstalled()
            }
            return
        }
        viewModelScope.launch {
            regionManager.downloadRegion(region, region.graphUrl)
            _installed.value = regionManager.isGraphInstalled()
        }
    }

    fun clearGraph() {
        regionManager.clearGraph()
        _installed.value = false
    }
}
