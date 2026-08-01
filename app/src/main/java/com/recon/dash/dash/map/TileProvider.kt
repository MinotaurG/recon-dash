package com.recon.dash.dash.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.LruCache
import com.recon.dash.dash.nav.GeoPoint
import com.recon.dash.map.TileSource
import com.recon.dash.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * OSM raster tile provider with memory + disk cache.
 *
 * Tiles are darkened ONCE at load (invert + desaturate + dim) and the dark bitmap
 * is cached, so the render loop never re-runs the colour matrix per frame — a key
 * power win at 4 fps.
 *
 * While riding, the process is bound to the Tripper's WiFi (no internet), so tiles
 * must come from cache — [prefetch]/[prefetchRoute] populate it while internet is
 * still reachable. Cache misses fetch through whichever network has connectivity
 * (cellular when bound to the dash WiFi), rate-limited to avoid hot loops.
 */
class TileProvider(context: Context, private val scope: CoroutineScope) {
    companion object {
        private const val TAG = "TileProvider"
        // OSM raster tile server — Play Store compliant, attribution required.
        // Used only as fallback when PMTiles file is not available.
        private const val URL_TEMPLATE =
            "https://tile.openstreetmap.org/%d/%d/%d.png"
        private const val USER_AGENT =
            "ReconDash/1.0 (motorcycle-nav; single-user; contact: github.com/MinotaurG)"
        private const val MAX_PREFETCH_TILES = 600
        private const val MIN_FETCH_GAP_MS = 250L // OSM rate limit: max 2 req/s
        private const val LOG_EVERY = 50L         // emit a TILESTAT line every N tile lookups
    }

    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val diskDir = File(context.cacheDir, "tiles_osm").apply { mkdirs() }
    private val memory = LruCache<String, Bitmap>(300)
    private val inflight = ConcurrentHashMap.newKeySet<String>()
    private val pmtiles = TileSource(context)
    @Volatile private var lastFetchAt = 0L

    /**
     * Re-open the offline pmtiles + drop cached tiles. Call after a region download so a
     * long-lived provider serves the newly-installed region instead of stale online tiles.
     */
    fun reloadOfflineTiles() {
        pmtiles.reload()
        memory.evictAll()
        DebugLog.i(TAG) { "TileProvider reloaded offline tiles (hasPMTiles=${pmtiles.hasPMTiles})" }
    }

    // Tile-source telemetry: how many tiles came from the offline PMTiles bundle vs. had to be
    // fetched online. Logged every LOG_EVERY tiles so a ride's offline-vs-online map coverage is
    // visible in the log (previously we could only INFER online fallback).
    private val pmtilesHits = java.util.concurrent.atomic.AtomicLong(0)
    private val onlineMisses = java.util.concurrent.atomic.AtomicLong(0)
    private fun logTileStatsMaybe() {
        val h = pmtilesHits.get(); val m = onlineMisses.get()
        if ((h + m) % LOG_EVERY == 0L && (h + m) > 0) {
            DebugLog.i(TAG) { "TILESTAT pmtilesHit=$h onlineMiss=$m (${if (h + m > 0) (h * 100 / (h + m)) else 0}% offline)" }
        }
    }

    /** Non-blocking: returns the cached tile, else kicks off async load. */
    fun get(z: Int, x: Int, y: Int): Bitmap? {
        val max = 1 shl z
        if (y < 0 || y >= max) return null
        val xw = ((x % max) + max) % max
        val key = "$z/$xw/$y"

        memory.get(key)?.let { return it }

        // Synchronous PMTiles lookup (fast — local file seek)
        if (pmtiles.hasPMTiles) {
            val bmp = pmtiles.getTile(z, xw, y)
            if (bmp != null) {
                pmtilesHits.incrementAndGet(); logTileStatsMaybe()
                memory.put(key, bmp)
                return bmp
            }
        }
        // Not served from the offline bundle -> will be fetched online below.
        onlineMisses.incrementAndGet(); logTileStatsMaybe()

        if (inflight.add(key)) {
            scope.launch(Dispatchers.IO) {
                try {
                    val raw = loadDisk(key) ?: fetch(z, xw, y, key)
                    if (raw != null) memory.put(key, raw)
                } finally {
                    inflight.remove(key)
                }
            }
        }
        return null
    }

    /** Prefetch tiles around a point (and optionally a straight corridor) into disk. */
    fun prefetch(lat: Double, lng: Double, fromLat: Double? = null, fromLng: Double? = null) {
        scope.launch(Dispatchers.IO) {
            var count = 0
            // 11..20 so the rider's vicinity has tiles at every zoom level offline
            // (default nav zoom is now 19, max 20).
            for (z in 11..20) {
                val radius = if (z in 15..16) 2 else 1
                count += prefetchAround(lat, lng, z, radius)
                if (fromLat != null && fromLng != null) {
                    count += prefetchAround(fromLat, fromLng, z, radius)
                    if (z in 12..13) for (i in 1..8) {
                        val f = i / 9.0
                        count += prefetchAround(fromLat + (lat - fromLat) * f, fromLng + (lng - fromLng) * f, z, 1)
                    }
                }
                if (count > MAX_PREFETCH_TILES) break
            }
            DebugLog.i(TAG) { "Prefetch (point) done — ~$count tiles ensured" }
        }
    }

    /** Prefetch tiles along the actual route polyline so offline riding has coverage. */
    fun prefetchRoute(route: List<GeoPoint>) {
        if (route.size < 2) return
        scope.launch(Dispatchers.IO) {
            var count = 0
            // Sample the polyline so we don't fetch a tile for every vertex.
            for (z in 12..16) {
                val seen = HashSet<String>()
                val step = if (z >= 15) 1 else 3
                var i = 0
                while (i < route.size) {
                    val p = route[i]
                    val cx = Mercator.lngToTileX(p.lng, z).toInt()
                    val cy = Mercator.latToTileY(p.lat, z).toInt()
                    val r = if (z >= 15) 1 else 0
                    for (dx in -r..r) for (dy in -r..r) {
                        val key = "$z/${cx + dx}/${cy + dy}"
                        if (seen.add(key) && !diskFile(key).exists()) {
                            fetchKey(z, cx + dx, cy + dy, key); count++
                        }
                    }
                    i += step
                    if (count > MAX_PREFETCH_TILES) break
                }
                if (count > MAX_PREFETCH_TILES) break
            }
            DebugLog.i(TAG) { "Prefetch (route) done — ~$count tiles ensured" }
        }
    }

    private fun prefetchAround(lat: Double, lng: Double, z: Int, radius: Int): Int {
        val cx = Mercator.lngToTileX(lng, z).toInt()
        val cy = Mercator.latToTileY(lat, z).toInt()
        var n = 0
        for (dx in -radius..radius) for (dy in -radius..radius) {
            val x = cx + dx; val y = cy + dy
            if (y < 0 || y >= (1 shl z)) continue
            val key = "$z/$x/$y"
            if (!diskFile(key).exists()) { fetchKey(z, x, y, key); n++ }
        }
        return n
    }

    // ── Internals ─────────────────────────────────────────────────────────

    private fun diskFile(key: String) = File(diskDir, key.replace('/', '_') + ".png")

    private fun loadDisk(key: String): Bitmap? {
        val f = diskFile(key)
        if (!f.exists()) return null
        return BitmapFactory.decodeFile(f.absolutePath)
    }

    private fun fetch(z: Int, x: Int, y: Int, key: String): Bitmap? = fetchKey(z, x, y, key)

    private fun fetchKey(z: Int, x: Int, y: Int, key: String): Bitmap? {
        val max = 1 shl z
        if (y < 0 || y >= max) return null
        // Rate-limit network fetches to avoid hot loops on the radio.
        val now = System.currentTimeMillis()
        val wait = MIN_FETCH_GAP_MS - (now - lastFetchAt)
        if (wait > 0) try { Thread.sleep(wait) } catch (_: InterruptedException) {}
        lastFetchAt = System.currentTimeMillis()

        val net = internetNetwork()
        return try {
            val url = URL(URL_TEMPLATE.format(z, x, ((y % max) + max) % max))
            val conn = (net?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            val bytes = conn.inputStream.use { it.readBytes() }
            conn.disconnect()
            diskFile(key).writeBytes(bytes)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            DebugLog.w(TAG) { "Tile $key fetch failed: ${e.message}" }
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun internetNetwork(): Network? =
        cm.allNetworks.firstOrNull { n ->
            cm.getNetworkCapabilities(n)?.let {
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            } == true
        }
}
