package com.recon.dash.data

import android.content.Context
import com.recon.dash.util.DebugLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/** The all-India routing extract (one pre-assembled valhalla_tiles.tar), versioned via manifest. */
data class RoutingInfo(val version: String, val url: String, val sizeMb: Int)

/** The all-India display map (pmtiles), versioned independently of routing. */
data class MapInfo(val version: String, val url: String, val sizeMb: Int)

/** Parsed manifest: display map + one all-India routing extract. From R2, cached. */
data class RoutingManifest(
    val map: MapInfo,
    val routing: RoutingInfo,
)

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
        private const val BUFFER_SIZE = 8192
        private const val BASE_URL = "https://pub-10f8e863c0f544798593ccdb61ffd2a9.r2.dev/"
        private const val MANIFEST_URL = "${BASE_URL}routing/v1/manifest.json"

        // One all-India routing extract (~4 GB) — pre-assembled server-side, downloaded straight to
        // the routable tar (no untar, no on-device assembly). Fallbacks if the manifest can't load.
        const val INDIA_ROUTING_URL = "${BASE_URL}routing/v1/india-routing.tar"
        const val INDIA_ROUTING_SIZE_MB = 4200

        // One all-India vector map for DISPLAY (~2 GB). Downloaded once, covers the whole country.
        const val INDIA_MAP_URL = "${BASE_URL}india/india.pmtiles"
        const val INDIA_MAP_SIZE_MB = 2018
        private const val PMTILES_DIR = "pmtiles"
        private const val PMTILES_FILE = "region.pmtiles"  // TileSource reads this exact name
    }

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState = _downloadState.asStateFlow()

    private val valhallaDir = File(context.filesDir, VALHALLA_DIR)
    // The routable extract the .so mmaps. We download the pre-assembled tar straight here — no loose
    // tile_dir, no on-device assembly, so routing data lives on disk exactly ONCE.
    private val tileExtractFile = File(valhallaDir, "valhalla_tiles.tar")
    private val prefs = context.getSharedPreferences("region_manager", Context.MODE_PRIVATE)

    // ── Manifest (single map + single routing entry) ────────────────────────

    @Volatile private var cachedManifest: RoutingManifest? = null

    /**
     * Fetch the manifest (display map + India routing) from R2, cached in memory + on disk. The
     * manifest is the single source of truth — bumping a map or routing version is a manifest edit
     * on R2, NO app update needed.
     */
    suspend fun manifest(forceRefresh: Boolean = false): RoutingManifest? = withContext(Dispatchers.IO) {
        if (!forceRefresh) cachedManifest?.let { return@withContext it }
        val json = runCatching {
            val conn = (URL(MANIFEST_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000; readTimeout = 30_000
            }
            conn.inputStream.bufferedReader().use { it.readText() }.also { conn.disconnect() }
        }.getOrElse { e ->
            DebugLog.w(TAG) { "Manifest fetch failed: ${e.message}; trying disk cache" }
            manifestCacheFile.takeIf { it.exists() }?.readText()
        } ?: return@withContext null

        runCatching {
            manifestCacheFile.writeText(json)   // cache for offline
            parseManifest(json).also { cachedManifest = it }
        }.getOrElse { e -> DebugLog.e(TAG, { "Manifest parse failed: ${e.message}" }, e); null }
    }

    private val manifestCacheFile: File get() = File(context.filesDir, "routing_manifest.json")

    private fun parseManifest(json: String): RoutingManifest {
        val o = JSONObject(json)
        val mo = o.getJSONObject("map")
        val map = MapInfo(mo.optString("version", "?"), mo.getString("url"), mo.getInt("sizeMb"))
        val ro = o.getJSONObject("routing")
        val routing = RoutingInfo(ro.optString("version", "?"), ro.getString("url"), ro.getInt("sizeMb"))
        return RoutingManifest(map, routing)
    }

    // ── Location → India check (for "you need India routing" prompts) ────────

    /** True when a coordinate falls inside India (a known state/UT), i.e. our routing covers it. */
    fun isInIndia(lat: Double, lng: Double): Boolean =
        geocoder.regionIdForLocation(lat, lng) != null

    // ── India routing extract (single download, streamed straight to the tar) ─

    /** True when the routable extract exists on disk. */
    fun isRoutingInstalled(): Boolean = tileExtractFile.exists() && tileExtractFile.length() > 0

    fun installedRoutingVersion(): String? = prefs.getString("installed_routing_version", null)

    /** True if the manifest's routing version differs from what's installed (update available). */
    fun routingUpdateAvailable(): Boolean {
        val installed = installedRoutingVersion() ?: return false
        val latest = cachedManifest?.routing?.version ?: return false
        return isRoutingInstalled() && installed != latest
    }

    /**
     * Download the all-India routing extract straight to [tileExtractFile]. It's pre-assembled
     * server-side, so we stream the .tar directly with no untar and no on-device assembly — the
     * routing data occupies disk exactly ONCE (~4 GB). Downloads to a temp file first, then renames
     * over the live tar so a failed/partial download never corrupts a working extract.
     */
    suspend fun downloadIndiaRouting(): Result<Unit> = withContext(Dispatchers.IO) {
        _downloadState.value = DownloadState.Downloading(0f, "India routing")
        try {
            val info = manifest()?.routing
            val url = info?.url ?: INDIA_ROUTING_URL
            val sizeMb = info?.sizeMb ?: INDIA_ROUTING_SIZE_MB
            valhallaDir.mkdirs()
            val tmp = File(context.cacheDir, "india-routing.tar.part")
            downloadFile(url, tmp, sizeMb * 1024L * 1024L, "India routing", 0f, 1f)
            if (tileExtractFile.exists()) tileExtractFile.delete()
            // Rename within the same filesystem is atomic; fall back to copy if it crosses stores.
            if (!tmp.renameTo(tileExtractFile)) {
                tmp.copyTo(tileExtractFile, overwrite = true); tmp.delete()
            }
            info?.version?.let { prefs.edit().putString("installed_routing_version", it).apply() }
            DebugLog.i(TAG) { "India routing installed (${tileExtractFile.length() / 1024 / 1024}MB, v${info?.version})" }
            _downloadState.value = DownloadState.Complete("India routing")
            Result.success(Unit)
        } catch (e: Exception) {
            _downloadState.value = DownloadState.Failed("India routing download failed: ${e.message}")
            DebugLog.e(TAG, { "India routing download failed: ${e.message}" }, e)
            Result.failure(e)
        }
    }

    /** Delete the routing extract. Leaves the India display map intact. */
    fun clearRouting() {
        tileExtractFile.delete()
        prefs.edit().remove("installed_routing_version").apply()
        _downloadState.value = DownloadState.Idle
    }

    // ── India display map ────────────────────────────────────────────────────

    private val pmtilesFile: File get() = File(File(context.filesDir, PMTILES_DIR), PMTILES_FILE)
    fun isIndiaMapInstalled(): Boolean = pmtilesFile.exists() && pmtilesFile.length() > 0
    fun indiaMapSizeMb(): Int = if (pmtilesFile.exists()) (pmtilesFile.length() / (1024 * 1024)).toInt() else 0

    suspend fun downloadIndiaMap(): Result<Unit> = withContext(Dispatchers.IO) {
        _downloadState.value = DownloadState.Downloading(0f, "India map")
        try {
            // Prefer the manifest's map url/size/version (code-free updates); fall back to the
            // baked-in constant if the manifest can't be fetched.
            val mapInfo = manifest()?.map
            val url = mapInfo?.url ?: INDIA_MAP_URL
            val sizeMb = mapInfo?.sizeMb ?: INDIA_MAP_SIZE_MB
            val pmtilesDir = File(context.filesDir, PMTILES_DIR).apply { mkdirs() }
            val dest = File(pmtilesDir, PMTILES_FILE)
            val tmp = File(context.cacheDir, "india.pmtiles.part")
            downloadFile(url, tmp, sizeMb * 1024L * 1024L, "India map", 0f, 1f)
            if (dest.exists()) dest.delete()
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
            // Record the installed map version so we can later show "map update available".
            mapInfo?.version?.let { prefs.edit().putString("installed_map_version", it).apply() }
            DebugLog.i(TAG) { "India map installed (${dest.length() / 1024 / 1024}MB, v${mapInfo?.version})" }
            _downloadState.value = DownloadState.Complete("India map")
            Result.success(Unit)
        } catch (e: Exception) {
            _downloadState.value = DownloadState.Failed("India map download failed: ${e.message}")
            Result.failure(e)
        }
    }

    /** Installed display-map version, or null if never downloaded. */
    fun installedMapVersion(): String? = prefs.getString("installed_map_version", null)

    /** True if the manifest's map version differs from what's installed (an update is available). */
    fun mapUpdateAvailable(): Boolean {
        val installed = installedMapVersion() ?: return false  // not installed = "download", not "update"
        val latest = cachedManifest?.map?.version ?: return false
        return isIndiaMapInstalled() && installed != latest
    }

    // ── Sizes / clearing ────────────────────────────────────────────────────

    /** On-disk size of everything installed (India map + routing extract), in MB. */
    fun installedSizeMb(): Int {
        var bytes = 0L
        if (tileExtractFile.exists()) bytes += tileExtractFile.length()
        if (pmtilesFile.exists()) bytes += pmtilesFile.length()
        return (bytes / (1024 * 1024)).toInt()
    }

    fun clearIndiaMap() {
        pmtilesFile.parentFile?.deleteRecursively()
        _downloadState.value = DownloadState.Idle
    }

    fun clearAll() { clearRouting(); clearIndiaMap() }

    // ── Download plumbing ────────────────────────────────────────────────────

    private fun downloadFile(
        url: String, destFile: File, totalEstimate: Long,
        regionName: String, progressStart: Float, progressEnd: Float,
    ) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 60_000
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
}
