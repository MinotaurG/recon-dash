package com.recon.dash.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.recon.dash.util.DebugLog
import java.io.File

/**
 * Tile source abstraction. Checks PMTiles first (fully offline),
 * then falls back to disk cache, then network fetch.
 *
 * For Play Store compliance, the network fallback uses OpenStreetMap
 * tile servers instead of Google. PMTiles is the primary source once
 * the region file is downloaded.
 */
class TileSource(context: Context) {

    companion object {
        private const val TAG = "TileSource"
        private const val PMTILES_DIR = "pmtiles"
    }

    private val pmtilesDir = File(context.filesDir, PMTILES_DIR)
    private var reader: PMTilesReader? = null

    init {
        pmtilesDir.mkdirs()
        openPMTiles()
    }

    @Volatile private var rasterizer: VectorTileRasterizer? = null

    val hasPMTiles: Boolean get() = reader?.isValid == true

    /** Vector archives need rasterizing (tens of ms per tile) — callers should load them off the render thread. */
    val isVector: Boolean get() = reader?.isVector == true

    /**
     * Bitmap for tile (z, x, y). Raster archives decode directly; vector (MVT) archives — the
     * all-India map — are rasterized, overzooming beyond the archive's max zoom from vector data.
     */
    fun getTile(z: Int, x: Int, y: Int): Bitmap? {
        val r = reader
        if (r != null && r.isValid) {
            if (r.isVector) return rasterizer?.render(z, x, y)
            val data = r.getTile(z, x, y) ?: return null
            return BitmapFactory.decodeByteArray(data, 0, data.size)
        }
        return null
    }

    fun installPMTilesFile(file: File): Boolean {
        val dest = File(pmtilesDir, "region.pmtiles")
        return try {
            file.copyTo(dest, overwrite = true)
            openPMTiles()
            reader?.isValid == true
        } catch (e: Exception) {
            DebugLog.e(TAG, { "Failed to install PMTiles: ${e.message}" }, e)
            false
        }
    }

    fun getInstalledFile(): File? {
        val f = File(pmtilesDir, "region.pmtiles")
        return if (f.exists()) f else null
    }

    /**
     * Re-open the on-disk region.pmtiles. Call after a region download replaces the file so a
     * long-lived TileSource picks up the new tiles WITHOUT an app restart (the reader is opened
     * once in init; a download writes the file directly and this refreshes the handle).
     */
    fun reload() {
        openPMTiles()
        DebugLog.i(TAG) { "Reloaded pmtiles: hasPMTiles=${reader?.isValid == true}" }
    }

    fun clear() {
        reader?.close()
        reader = null
        rasterizer = null
        pmtilesDir.listFiles()?.forEach { it.delete() }
    }

    fun close() {
        reader?.close()
        reader = null
        rasterizer = null
    }

    private fun openPMTiles() {
        reader?.close()
        val file = File(pmtilesDir, "region.pmtiles")
        if (file.exists()) {
            val r = PMTilesReader(file)
            if (r.open()) {
                reader = r
                rasterizer = if (r.isVector) VectorTileRasterizer(r::getTile, r.maxZoom) else null
                DebugLog.i(TAG) { "PMTiles source active: ${file.name} (${file.length() / 1024 / 1024}MB)" }
            } else {
                DebugLog.w(TAG) { "PMTiles file exists but failed to open" }
            }
        }
    }
}
