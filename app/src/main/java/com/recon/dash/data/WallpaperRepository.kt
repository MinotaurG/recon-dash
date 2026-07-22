package com.recon.dash.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.recon.dash.util.DebugLog
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

data class Wallpaper(
    val id: String,
    val fileName: String,
    val isCustom: Boolean = false,
)

@Singleton
class WallpaperRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        private const val TAG = "WallpaperRepo"
        private const val ASSET_DIR = "wallpapers"
        private const val CUSTOM_DIR = "wallpapers"
        private const val PREF_NAME = "wallpaper_prefs"
        private const val KEY_SELECTED = "selected_wallpaper"
        private const val CACHE_FILE = "wallpaper_current.png"
    }

    private val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    /** Internal storage for rider-supplied images (survives app updates, private to app). */
    private val customDir: File by lazy {
        File(context.filesDir, CUSTOM_DIR).apply { mkdirs() }
    }

    /** Bundled assets first, then the rider's own uploads. */
    fun listWallpapers(): List<Wallpaper> = listAssetWallpapers() + listCustomWallpapers()

    private fun listAssetWallpapers(): List<Wallpaper> {
        return try {
            context.assets.list(ASSET_DIR)
                ?.filter { it.endsWith(".png") || it.endsWith(".jpg") }
                ?.sorted()
                ?.map { Wallpaper(id = it.removeSuffix(".png").removeSuffix(".jpg"), fileName = it) }
                ?: emptyList()
        } catch (e: Exception) {
            DebugLog.w(TAG) { "Failed to list asset wallpapers: ${e.message}" }
            emptyList()
        }
    }

    private fun listCustomWallpapers(): List<Wallpaper> {
        return try {
            customDir.listFiles { f -> f.isFile && (f.name.endsWith(".png") || f.name.endsWith(".jpg")) }
                ?.sortedByDescending { it.lastModified() }
                ?.map { Wallpaper(id = "Custom", fileName = it.name, isCustom = true) }
                ?: emptyList()
        } catch (e: Exception) {
            DebugLog.w(TAG) { "Failed to list custom wallpapers: ${e.message}" }
            emptyList()
        }
    }

    /**
     * Copy a picked image (content URI from the photo picker) into internal storage.
     * Returns the new [Wallpaper], or null on failure. Decoding into a Bitmap first
     * normalises the format and rejects anything that isn't a real image.
     */
    fun importFromUri(uri: Uri): Wallpaper? {
        return try {
            val bmp = context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input)
            } ?: return null
            val fileName = "custom_${System.currentTimeMillis()}.png"
            File(customDir, fileName).outputStream().use { out ->
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            bmp.recycle()
            Wallpaper(id = "Custom", fileName = fileName, isCustom = true)
        } catch (e: Exception) {
            DebugLog.w(TAG) { "Failed to import wallpaper: ${e.message}" }
            null
        }
    }

    /** Delete a rider-supplied wallpaper. Clears the selection if it was the active one. */
    fun deleteCustom(fileName: String) {
        runCatching { File(customDir, fileName).delete() }
        if (getSelected() == fileName) clearSelected()
    }

    fun getSelected(): String? = prefs.getString(KEY_SELECTED, null)

    fun setSelected(fileName: String) {
        prefs.edit().putString(KEY_SELECTED, fileName).apply()
        File(context.cacheDir, CACHE_FILE).delete()
    }

    /** No wallpaper — the dash idle screen falls back to the plain dark background. */
    fun clearSelected() {
        prefs.edit().remove(KEY_SELECTED).apply()
        File(context.cacheDir, CACHE_FILE).delete()
    }

    fun loadBitmap(fileName: String, targetWidth: Int, targetHeight: Int): Bitmap? {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            openStream(fileName)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null

            opts.inSampleSize = calculateInSampleSize(opts, targetWidth, targetHeight)
            opts.inJustDecodeBounds = false

            val bmp = openStream(fileName)?.use { BitmapFactory.decodeStream(it, null, opts) }
            bmp?.let { Bitmap.createScaledBitmap(it, targetWidth, targetHeight, true) }
        } catch (e: Exception) {
            DebugLog.w(TAG) { "Failed to load wallpaper $fileName: ${e.message}" }
            null
        }
    }

    /** Resolve a wallpaper name to a stream — a custom file if present, else a bundled asset. */
    private fun openStream(fileName: String): InputStream? {
        val custom = File(customDir, fileName)
        return if (custom.exists()) custom.inputStream()
        else context.assets.open("$ASSET_DIR/$fileName")
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqW: Int, reqH: Int): Int {
        val (h, w) = options.outHeight to options.outWidth
        var inSampleSize = 1
        if (h > reqH || w > reqW) {
            val halfH = h / 2
            val halfW = w / 2
            while (halfH / inSampleSize >= reqH && halfW / inSampleSize >= reqW) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }
}
