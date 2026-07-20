package com.recon.dash.ui.settings

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import com.recon.dash.data.WallpaperRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class WallpaperPickerViewModel @Inject constructor(
    private val repo: WallpaperRepository,
) : ViewModel() {

    private val _wallpapers = MutableStateFlow(repo.listWallpapers())
    val wallpapers = _wallpapers.asStateFlow()

    private val _selected = MutableStateFlow(repo.getSelected() ?: "")
    val selected = _selected.asStateFlow()

    private val thumbnailCache = HashMap<String, Bitmap?>()

    fun select(fileName: String) {
        repo.setSelected(fileName)
        _selected.value = fileName
    }

    fun loadThumbnail(fileName: String): Bitmap? {
        return thumbnailCache.getOrPut(fileName) {
            repo.loadBitmap(fileName, 263, 150)
        }
    }
}
