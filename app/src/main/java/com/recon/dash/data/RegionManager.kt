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

/** One downloadable routing pack (a state/UT, or the base skeleton). */
data class RoutingPack(
    val id: String,
    val name: String,
    val sizeMb: Int,
    val url: String,
)

/** A zone groups several state/UT packs for the download UI (e.g. South = KA, KL, TN, TG, AP). */
data class RoutingZone(
    val id: String,
    val name: String,
    val states: List<RoutingPack>,
) {
    val sizeMb: Int get() = states.sumOf { it.sizeMb }
}

/** The all-India display map entry (pmtiles), versioned independently of routing. */
data class MapInfo(val version: String, val url: String, val sizeMb: Int)

/** Parsed manifest: display map + routing (base pack + zones of state packs). From R2, cached. */
data class RoutingManifest(
    val map: MapInfo,
    val routingVersion: String,
    val base: RoutingPack,
    val zones: List<RoutingZone>,
) {
    val allStates: List<RoutingPack> get() = zones.flatMap { it.states }
    fun state(id: String): RoutingPack? = allStates.firstOrNull { it.id == id }
    val totalMb: Int get() = base.sizeMb + allStates.sumOf { it.sizeMb }
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
        // Packs extract loose .gph tiles into this shared dir; Valhalla routes across them (tile_dir).
        private const val TILE_SUBDIR = "valhalla_tiles"
        private const val BUFFER_SIZE = 8192
        private const val BASE_URL = "https://pub-10f8e863c0f544798593ccdb61ffd2a9.r2.dev/"
        private const val MANIFEST_URL = "${BASE_URL}routing/v1/manifest.json"

        // One all-India vector map for DISPLAY (~2 GB). Downloaded once, covers the whole country.
        const val INDIA_MAP_URL = "${BASE_URL}india/india.pmtiles"
        const val INDIA_MAP_SIZE_MB = 2018
        private const val PMTILES_DIR = "pmtiles"
        private const val PMTILES_FILE = "region.pmtiles"  // TileSource reads this exact name

        private const val BASE_PACK_ID = "base"
    }

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState = _downloadState.asStateFlow()

    private val valhallaDir = File(context.filesDir, VALHALLA_DIR)
    private val tileGraphDir = File(valhallaDir, TILE_SUBDIR)
    private val prefs = context.getSharedPreferences("region_manager", Context.MODE_PRIVATE)

    // ── Manifest (zones + state packs) ──────────────────────────────────────

    @Volatile private var cachedManifest: RoutingManifest? = null

    /**
     * Fetch the routing manifest (base + zones of state packs) from R2, cached in memory + on disk.
     * The manifest is the single source of truth for what's downloadable — adding a state or bumping
     * a map version is a manifest change on R2, NO app update needed.
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
        val b = ro.getJSONObject("base")
        val base = RoutingPack(BASE_PACK_ID, "Base map (highways)", b.getInt("sizeMb"), b.getString("url"))
        val zones = mutableListOf<RoutingZone>()
        val zarr = ro.getJSONArray("zones")
        for (i in 0 until zarr.length()) {
            val z = zarr.getJSONObject(i)
            val states = mutableListOf<RoutingPack>()
            val sarr = z.getJSONArray("states")
            for (j in 0 until sarr.length()) {
                val s = sarr.getJSONObject(j)
                states += RoutingPack(s.getString("id"), s.getString("name"), s.getInt("sizeMb"), s.getString("url"))
            }
            zones += RoutingZone(z.getString("id"), z.getString("name"), states)
        }
        return RoutingManifest(map, ro.optString("version", "?"), base, zones)
    }

    // ── Installed-pack tracking (a SET now — packs stack) ───────────────────

    /** Ids of routing packs currently extracted into the shared tile_dir (incl. "base"). */
    fun installedPackIds(): Set<String> =
        prefs.getStringSet("installed_packs", emptySet()) ?: emptySet()

    private fun markInstalled(id: String) {
        prefs.edit().putStringSet("installed_packs", installedPackIds() + id).apply()
    }
    private fun markRemoved(id: String) {
        prefs.edit().putStringSet("installed_packs", installedPackIds() - id).apply()
    }

    fun isPackInstalled(id: String): Boolean = installedPackIds().contains(id)
    fun isBaseInstalled(): Boolean = isPackInstalled(BASE_PACK_ID)

    /** True when routing is usable: base pack present (holds the L0/L1 skeleton every route needs). */
    fun isGraphInstalled(): Boolean =
        isBaseInstalled() && File(tileGraphDir, "0").let { it.isDirectory && (it.listFiles()?.isNotEmpty() == true) }

    // ── State/coordinate → pack (for "download the state you're in" prompts) ──

    /** The state pack id covering a coordinate, or null outside India / on load failure. */
    fun stateIdForLocation(lat: Double, lng: Double): String? =
        geocoder.regionIdForLocation(lat, lng)

    /** Manifest pack for a coordinate (so a failed route can prompt the exact state to download). */
    suspend fun packForLocation(lat: Double, lng: Double): RoutingPack? {
        val sid = stateIdForLocation(lat, lng) ?: return null
        return manifest()?.state(sid)
    }

    /**
     * Synchronous: the display name of an un-installed state pack covering a location, using only
     * the CACHED manifest (no network). For non-suspend UI paths (e.g. "you could have this
     * offline" hint). Returns null if in an installed state, outside India, or manifest not cached.
     */
    fun uninstalledPackNameForLocation(lat: Double, lng: Double): String? {
        val sid = stateIdForLocation(lat, lng) ?: return null
        if (isPackInstalled(sid)) return null
        return cachedManifest?.state(sid)?.name
    }

    // ── Downloading packs (stack into the shared tile_dir) ──────────────────

    /**
     * Download a routing pack and EXTRACT it into the shared tile_dir so it stacks with any
     * already-installed packs. The base pack is auto-included: routing needs it, so if it's not
     * installed we fetch it first.
     */
    suspend fun downloadPack(pack: RoutingPack): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // Ensure base is present first (every route needs the L0/L1 skeleton).
            if (pack.id != BASE_PACK_ID && !isBaseInstalled()) {
                val base = manifest()?.base
                    ?: return@withContext Result.failure(IllegalStateException("No manifest for base pack"))
                extractPack(base)
            }
            extractPack(pack)
            _downloadState.value = DownloadState.Complete(pack.name)
            Result.success(Unit)
        } catch (e: Exception) {
            _downloadState.value = DownloadState.Failed("Download failed: ${e.message}")
            DebugLog.e(TAG, { "Pack ${pack.id} download failed: ${e.message}" }, e)
            Result.failure(e)
        }
    }

    /** Download a whole zone (all its state packs), base included. */
    suspend fun downloadZone(zone: RoutingZone): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            manifest()?.base?.let { if (!isBaseInstalled()) extractPack(it) }
            for (p in zone.states) extractPack(p)
            _downloadState.value = DownloadState.Complete(zone.name)
            Result.success(Unit)
        } catch (e: Exception) {
            _downloadState.value = DownloadState.Failed("Zone download failed: ${e.message}")
            Result.failure(e)
        }
    }

    /** Download the ENTIRE country — base + every state pack. */
    suspend fun downloadAll(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val m = manifest() ?: return@withContext Result.failure(IllegalStateException("No manifest"))
            extractPack(m.base)
            for (p in m.allStates) if (!isPackInstalled(p.id)) extractPack(p)
            _downloadState.value = DownloadState.Complete("All India")
            Result.success(Unit)
        } catch (e: Exception) {
            _downloadState.value = DownloadState.Failed("Download all failed: ${e.message}")
            Result.failure(e)
        }
    }

    /** Download a pack's .tar to cache, untar into the shared tile_dir, record it installed. */
    private fun extractPack(pack: RoutingPack) {
        if (isPackInstalled(pack.id)) return
        _downloadState.value = DownloadState.Downloading(0f, pack.name)
        tileGraphDir.mkdirs()
        val tmp = File(context.cacheDir, "pack_${pack.id}.tar")
        try {
            downloadFile(pack.url, tmp, pack.sizeMb * 1024L * 1024L, pack.name, 0f, 0.9f)
            untarInto(tmp, tileGraphDir)
            markInstalled(pack.id)
            DebugLog.i(TAG) { "Pack ${pack.id} extracted into tile_dir (${pack.sizeMb}MB)" }
        } finally {
            tmp.delete()
        }
    }

    /** Untar a routing pack (loose .gph at paths like 0/002/854.gph) into [dest]. */
    private fun untarInto(tar: File, dest: File) {
        java.io.BufferedInputStream(java.io.FileInputStream(tar)).use { raw ->
            val tin = org.apache.commons.compress.archivers.tar.TarArchiveInputStream(raw)
            var entry = tin.nextEntry
            val buf = ByteArray(BUFFER_SIZE)
            while (entry != null) {
                val out = File(dest, entry.name)
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { fos ->
                        var n = tin.read(buf)
                        while (n != -1) { fos.write(buf, 0, n); n = tin.read(buf) }
                    }
                }
                entry = tin.nextEntry
            }
        }
    }

    // ── India display map (unchanged) ───────────────────────────────────────

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

    /** On-disk size of everything installed (India map + all routing packs), in MB. */
    fun installedSizeMb(): Int {
        var bytes = 0L
        if (tileGraphDir.exists()) bytes += tileGraphDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        if (pmtilesFile.exists()) bytes += pmtilesFile.length()
        return (bytes / (1024 * 1024)).toInt()
    }

    /** Delete ALL routing packs (frees the tile_dir). Leaves the India display map intact. */
    fun clearGraph() {
        tileGraphDir.deleteRecursively()
        prefs.edit().remove("installed_packs").apply()
        _downloadState.value = DownloadState.Idle
    }

    fun clearIndiaMap() {
        pmtilesFile.parentFile?.deleteRecursively()
        _downloadState.value = DownloadState.Idle
    }

    fun clearAll() { clearGraph(); clearIndiaMap() }

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
