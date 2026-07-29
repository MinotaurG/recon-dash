package com.recon.dash.data

import android.content.Context
import com.recon.dash.util.DebugLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

data class Region(
    val id: String,
    val name: String,
    val graphUrl: String,
    val tilesUrl: String = "",
    val graphSizeMb: Int,
    val tilesSizeMb: Int = 0,
) {
    val totalSizeMb: Int get() = graphSizeMb + tilesSizeMb
}

sealed class DownloadState {
    object Idle : DownloadState()
    data class Downloading(val progress: Float, val regionName: String) : DownloadState()
    data class Complete(val regionName: String) : DownloadState()
    data class Failed(val message: String) : DownloadState()
}

@Singleton
class RegionManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        private const val TAG = "RegionManager"
        private const val VALHALLA_DIR = "valhalla"
        private const val TILES_FILE = "valhalla_tiles.tar"
        private const val BUFFER_SIZE = 8192
        private const val BASE_URL = "https://pub-10f8e863c0f544798593ccdb61ffd2a9.r2.dev/"
    }

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState = _downloadState.asStateFlow()

    private val valhallaDir = File(context.filesDir, VALHALLA_DIR)
    private val tilesFile = File(valhallaDir, TILES_FILE)

    private val prefs = context.getSharedPreferences("region_manager", Context.MODE_PRIVATE)

    /** The region id whose tiles are currently installed, or null if none/unknown. */
    fun installedRegionId(): String? =
        if (isGraphInstalled()) prefs.getString("installed_region_id", null) else null

    val availableRegions: List<Region> = listOf(
        // South
        Region("karnataka", "Karnataka", "", "", graphSizeMb = 80, tilesSizeMb = 60),
        Region("tamil_nadu", "Tamil Nadu", "", "", graphSizeMb = 90, tilesSizeMb = 70),
        Region("kerala", "Kerala", "", "", graphSizeMb = 45, tilesSizeMb = 35),
        Region("andhra_pradesh", "Andhra Pradesh", "", "", graphSizeMb = 100, tilesSizeMb = 75),
        Region("telangana", "Telangana",
            graphUrl = "${BASE_URL}telangana/valhalla_tiles.tar",
            tilesUrl = "${BASE_URL}telangana/tiles.pmtiles",
            graphSizeMb = 299, tilesSizeMb = 132),
        // West
        Region("maharashtra", "Maharashtra",
            graphUrl = "${BASE_URL}maharashtra/valhalla_tiles.tar",
            tilesUrl = "${BASE_URL}maharashtra/tiles.pmtiles",
            graphSizeMb = 759, tilesSizeMb = 381),
        Region("goa", "Goa", "", "", graphSizeMb = 15, tilesSizeMb = 10),
        Region("gujarat", "Gujarat", "", "", graphSizeMb = 130, tilesSizeMb = 95),
        Region("rajasthan", "Rajasthan", "", "", graphSizeMb = 120, tilesSizeMb = 90),
        // North
        Region("delhi_ncr", "Delhi NCR", "", "", graphSizeMb = 35, tilesSizeMb = 25),
        Region("uttar_pradesh", "Uttar Pradesh", "", "", graphSizeMb = 200, tilesSizeMb = 150),
        Region("madhya_pradesh", "Madhya Pradesh", "", "", graphSizeMb = 140, tilesSizeMb = 100),
        Region("punjab", "Punjab", "", "", graphSizeMb = 50, tilesSizeMb = 38),
        Region("haryana", "Haryana", "", "", graphSizeMb = 45, tilesSizeMb = 32),
        Region("himachal", "Himachal Pradesh", "", "", graphSizeMb = 50, tilesSizeMb = 40),
        Region("uttarakhand", "Uttarakhand", "", "", graphSizeMb = 55, tilesSizeMb = 45),
        Region("jammu_kashmir", "Jammu & Kashmir", "", "", graphSizeMb = 45, tilesSizeMb = 35),
        Region("ladakh", "Ladakh", "", "", graphSizeMb = 20, tilesSizeMb = 15),
        // East
        Region("west_bengal", "West Bengal", "", "", graphSizeMb = 85, tilesSizeMb = 60),
        Region("odisha", "Odisha", "", "", graphSizeMb = 75, tilesSizeMb = 55),
        Region("bihar", "Bihar", "", "", graphSizeMb = 80, tilesSizeMb = 58),
        Region("jharkhand", "Jharkhand", "", "", graphSizeMb = 55, tilesSizeMb = 40),
        Region("chhattisgarh", "Chhattisgarh", "", "", graphSizeMb = 60, tilesSizeMb = 45),
        // Northeast
        Region("assam", "Assam", "", "", graphSizeMb = 45, tilesSizeMb = 32),
        Region("sikkim", "Sikkim", "", "", graphSizeMb = 12, tilesSizeMb = 8),
        Region("meghalaya", "Meghalaya", "", "", graphSizeMb = 18, tilesSizeMb = 12),
        Region("arunachal", "Arunachal Pradesh", "", "", graphSizeMb = 25, tilesSizeMb = 18),
        Region("nagaland", "Nagaland", "", "", graphSizeMb = 14, tilesSizeMb = 10),
        Region("manipur", "Manipur", "", "", graphSizeMb = 15, tilesSizeMb = 10),
        Region("mizoram", "Mizoram", "", "", graphSizeMb = 12, tilesSizeMb = 8),
        Region("tripura", "Tripura", "", "", graphSizeMb = 10, tilesSizeMb = 7),
        // UTs
        Region("chandigarh", "Chandigarh", "", "", graphSizeMb = 5, tilesSizeMb = 3),
        Region("puducherry", "Puducherry", "", "", graphSizeMb = 4, tilesSizeMb = 3),
        Region("andaman", "Andaman & Nicobar", "", "", graphSizeMb = 6, tilesSizeMb = 4),
    )

    /**
     * Approximate state lookup by coordinate — used to suggest which region to download when a
     * route fails because the rider is outside the installed extract. Bounding boxes are rough and
     * can overlap; first match wins. Returns null outside covered India.
     */
    fun regionForLocation(lat: Double, lng: Double): Region? {
        val id = when {
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
            else -> null
        }
        return id?.let { rid -> availableRegions.firstOrNull { it.id == rid } }
    }

    /** True when a region has real download URLs (i.e. its tiles are built + hosted). */
    fun isRegionAvailable(region: Region): Boolean = region.graphUrl.isNotBlank()

    fun isGraphInstalled(): Boolean =
        tilesFile.exists() && tilesFile.length() > 0

    fun installedSizeMb(): Int {
        if (!tilesFile.exists()) return 0
        return (tilesFile.length() / (1024 * 1024)).toInt()
    }

    suspend fun downloadRegion(region: Region, url: String): Result<Unit> = withContext(Dispatchers.IO) {
        _downloadState.value = DownloadState.Downloading(0f, region.name)

        try {
            val totalEstimate = region.totalSizeMb * 1024L * 1024L

            // Download Valhalla tile extract (.tar) directly — no unzip needed.
            valhallaDir.mkdirs()
            val tmp = File(context.cacheDir, "region_tiles.tar")
            downloadFile(region.graphUrl, tmp, totalEstimate, region.name, 0f, 0.65f)
            if (tilesFile.exists()) tilesFile.delete()
            tmp.copyTo(tilesFile, overwrite = true)
            tmp.delete()
            DebugLog.i(TAG) { "Valhalla tiles installed for ${region.name} (${tilesFile.length() / 1024 / 1024}MB)" }

            // Download PMTiles map (if URL exists)
            if (region.tilesUrl.isNotBlank()) {
                val pmtilesDir = File(context.filesDir, "pmtiles")
                pmtilesDir.mkdirs()
                val destFile = File(pmtilesDir, "region.pmtiles")
                downloadFile(region.tilesUrl, destFile, totalEstimate, region.name, 0.65f, 1f)
                DebugLog.i(TAG) { "PMTiles installed for ${region.name}" }
            }

            prefs.edit().putString("installed_region_id", region.id).apply()
            _downloadState.value = DownloadState.Complete(region.name)
            Result.success(Unit)
        } catch (e: Exception) {
            _downloadState.value = DownloadState.Failed("Download failed: ${e.message}")
            DebugLog.e(TAG, { "Download failed: ${e.message}" }, e)
            Result.failure(e)
        }
    }

    private fun downloadFile(
        url: String,
        destFile: File,
        totalEstimate: Long,
        regionName: String,
        progressStart: Float,
        progressEnd: Float,
    ) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
        }
        val fileSize = conn.contentLengthLong.takeIf { it > 0 } ?: (totalEstimate / 2)

        conn.inputStream.use { input ->
            FileOutputStream(destFile).use { output ->
                val buffer = ByteArray(BUFFER_SIZE)
                var downloaded = 0L
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    output.write(buffer, 0, read)
                    downloaded += read
                    val fileProgress = (downloaded.toFloat() / fileSize).coerceIn(0f, 1f)
                    val overall = progressStart + fileProgress * (progressEnd - progressStart)
                    _downloadState.value = DownloadState.Downloading(overall, regionName)
                }
            }
        }
        conn.disconnect()
    }

    fun clearGraph() {
        valhallaDir.deleteRecursively()
        prefs.edit().remove("installed_region_id").apply()
        _downloadState.value = DownloadState.Idle
    }
}
