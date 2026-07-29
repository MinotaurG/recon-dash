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
    private val geocoder: RegionGeocoder,
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

    /**
     * Offline units are ZONAL BUNDLES, not single states. A bundle covers several states so a
     * rider routes seamlessly across the states they'd actually cross (Mumbai->Goa, Hyderabad->
     * Bangalore) without the single-state-tile problem where one download overwrote another and
     * cross-border routes broke. Only one bundle is installed at a time (see installedRegionId).
     *
     * URLs are set once a zone's tiles are built + uploaded to R2 (empty = "Coming soon").
     */
    val availableRegions: List<Region> = listOf(
        Region("west", "West India (MH, GJ, GA, MP)",
            graphUrl = "${BASE_URL}west/valhalla_tiles.tar",
            tilesUrl = "${BASE_URL}west/tiles.pmtiles",
            graphSizeMb = 1536, tilesSizeMb = 822),
        Region("south", "South India (KA, KL, TN, TG, AP)",
            graphUrl = "${BASE_URL}south/valhalla_tiles.tar",
            tilesUrl = "${BASE_URL}south/tiles.pmtiles",
            graphSizeMb = 0, tilesSizeMb = 0),
        Region("north", "North India (DL, PB, HR, RJ, UP, UK, HP)", "", "", graphSizeMb = 0),
        Region("east", "East India (WB, OD, BR, JH, CG)", "", "", graphSizeMb = 0),
        Region("northeast", "Northeast India (AS + 7 sisters)", "", "", graphSizeMb = 0),
    )

    /**
     * State id (from [RegionGeocoder]) -> zonal bundle id. This is what turns a GPS fix into the
     * bundle to download. Kept exhaustive so every geocodable state maps to a zone.
     */
    private val stateToZone: Map<String, String> = mapOf(
        // West
        "maharashtra" to "west", "goa" to "west", "gujarat" to "west", "madhya_pradesh" to "west",
        // South
        "karnataka" to "south", "kerala" to "south", "tamil_nadu" to "south",
        "telangana" to "south", "andhra_pradesh" to "south",
        // North
        "delhi_ncr" to "north", "punjab" to "north", "haryana" to "north", "rajasthan" to "north",
        "uttar_pradesh" to "north", "uttarakhand" to "north", "himachal" to "north",
        "jammu_kashmir" to "north", "ladakh" to "north",
        // East
        "west_bengal" to "east", "odisha" to "east", "bihar" to "east",
        "jharkhand" to "east", "chhattisgarh" to "east",
        // Northeast
        "assam" to "northeast", "sikkim" to "northeast", "meghalaya" to "northeast",
        "arunachal" to "northeast", "nagaland" to "northeast",
        "manipur" to "northeast", "mizoram" to "northeast", "tripura" to "northeast",
    )

    /** The zonal bundle covering a given state id, or null if that state isn't in any zone. */
    fun zoneForState(stateId: String): Region? =
        stateToZone[stateId]?.let { zid -> availableRegions.firstOrNull { it.id == zid } }

    /**
     * State lookup by coordinate (point-in-polygon via [RegionGeocoder]) — used to suggest which
     * region to download when a route fails because the rider is outside the installed extract.
     * Returns null outside covered India / on load failure.
     */
    fun regionForLocation(lat: Double, lng: Double): Region? {
        val stateId = geocoder.regionIdForLocation(lat, lng) ?: return null
        // Map the state the rider is in to its zonal bundle (the actual downloadable unit).
        return zoneForState(stateId)
    }

    /** True when a region has real download URLs (i.e. its tiles are built + hosted). */
    fun isRegionAvailable(region: Region): Boolean = region.graphUrl.isNotBlank()

    fun isGraphInstalled(): Boolean =
        tilesFile.exists() && tilesFile.length() > 0

    private val pmtilesFile: File
        get() = File(File(context.filesDir, "pmtiles"), "region.pmtiles")

    /** On-disk size of everything an installed region occupies (routing graph + map tiles), in MB. */
    fun installedSizeMb(): Int {
        var bytes = 0L
        if (tilesFile.exists()) bytes += tilesFile.length()
        if (pmtilesFile.exists()) bytes += pmtilesFile.length()
        return (bytes / (1024 * 1024)).toInt()
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

    /** Delete ALL installed offline data (routing graph + map tiles) to free device space. */
    fun clearGraph() {
        valhallaDir.deleteRecursively()
        pmtilesFile.parentFile?.deleteRecursively()   // also drop the ~hundreds-of-MB pmtiles
        prefs.edit().remove("installed_region_id").apply()
        _downloadState.value = DownloadState.Idle
    }
}
