package com.recon.dash.ui.settings

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.recon.dash.data.Wallpaper
import com.recon.dash.data.WallpaperRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class WallpaperPickerViewModel @Inject constructor(
    private val repo: WallpaperRepository,
) : ViewModel() {

    private val _wallpapers = MutableStateFlow(repo.listWallpapers())
    val wallpapers = _wallpapers.asStateFlow()

    /** Empty string = "None" (no wallpaper): the dash idle screen shows a plain dark background. */
    private val _selected = MutableStateFlow(repo.getSelected() ?: "")
    val selected = _selected.asStateFlow()

    private val thumbnailCache = HashMap<String, Bitmap?>()

    fun select(fileName: String) {
        repo.setSelected(fileName)
        _selected.value = fileName
    }

    /** Choose "None" — clears the stored selection so the dash falls back to the dark background. */
    fun selectNone() {
        repo.clearSelected()
        _selected.value = ""
    }

    /** Import a rider-supplied image from the photo picker and select it immediately. */
    fun importWallpaper(uri: Uri) {
        viewModelScope.launch {
            val added = withContext(Dispatchers.IO) { repo.importFromUri(uri) }
            if (added != null) {
                _wallpapers.value = repo.listWallpapers()
                select(added.fileName)
            }
        }
    }

    fun deleteWallpaper(wp: Wallpaper) {
        if (!wp.isCustom) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.deleteCustom(wp.fileName) }
            thumbnailCache.remove(wp.fileName)
            _wallpapers.value = repo.listWallpapers()
            if (_selected.value == wp.fileName) _selected.value = repo.getSelected() ?: ""
        }
    }

    fun loadThumbnail(fileName: String): Bitmap? {
        return thumbnailCache.getOrPut(fileName) {
            repo.loadBitmap(fileName, 263, 150)
        }
    }
}
