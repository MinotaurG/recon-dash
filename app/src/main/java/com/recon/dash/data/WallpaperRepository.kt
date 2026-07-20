package com.recon.dash.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.recon.dash.util.DebugLog
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class Wallpaper(
    val id: String,
    val fileName: String,
)

@Singleton
class WallpaperRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        private const val TAG = "WallpaperRepo"
        private const val ASSET_DIR = "wallpapers"
        private const val PREF_NAME = "wallpaper_prefs"
        private const val KEY_SELECTED = "selected_wallpaper"
    }

    private val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun listWallpapers(): List<Wallpaper> {
        return try {
            context.assets.list(ASSET_DIR)
                ?.filter { it.endsWith(".png") || it.endsWith(".jpg") }
                ?.sorted()
                ?.map { Wallpaper(id = it.removeSuffix(".png").removeSuffix(".jpg"), fileName = it) }
                ?: emptyList()
        } catch (e: Exception) {
            DebugLog.w(TAG) { "Failed to list wallpapers: ${e.message}" }
            emptyList()
        }
    }

    fun getSelected(): String? = prefs.getString(KEY_SELECTED, null)

    fun setSelected(fileName: String) {
        prefs.edit().putString(KEY_SELECTED, fileName).apply()
        java.io.File(context.cacheDir, "wallpaper_current.png").delete()
    }

    fun loadBitmap(fileName: String, targetWidth: Int, targetHeight: Int): Bitmap? {
        return try {
            val input = context.assets.open("$ASSET_DIR/$fileName")
            val opts = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeStream(input, null, opts)
            input.close()

            opts.inSampleSize = calculateInSampleSize(opts, targetWidth, targetHeight)
            opts.inJustDecodeBounds = false

            val input2 = context.assets.open("$ASSET_DIR/$fileName")
            val bmp = BitmapFactory.decodeStream(input2, null, opts)
            input2.close()

            bmp?.let { Bitmap.createScaledBitmap(it, targetWidth, targetHeight, true) }
        } catch (e: Exception) {
            DebugLog.w(TAG) { "Failed to load wallpaper $fileName: ${e.message}" }
            null
        }
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
