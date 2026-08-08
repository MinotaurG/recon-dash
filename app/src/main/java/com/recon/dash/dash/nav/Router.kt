package com.recon.dash.dash.nav

import android.content.Context
import com.recon.dash.util.DebugLog
import com.valhalla.valhalla.ValhallaConfig
import com.valhalla.valhalla.ValhallaKotlin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File

sealed class RouterError {
    data class GraphNotLoaded(val message: String) : RouterError()
    data class NoRouteFound(val from: GeoPoint, val to: GeoPoint) : RouterError()
    data class RoutingFailed(val cause: Throwable) : RouterError()
}

sealed class RouterResult {
    data class Success(val route: Route, val alternatives: List<Route> = emptyList()) : RouterResult()
    data class Failure(val error: RouterError) : RouterResult()
}

/**
 * Ride mode — a preset of Valhalla motorcycle-costing values. Valhalla has no named "modes"; it's
 * one `motorcycle` costing shaped by 0..1 preference knobs. These presets pick sensible bundles:
 *   use_highways    0=avoid .. 1=prefer   (default 0.5 = neutral)
 *   use_trails      willingness to use rough/unpaved tracks (motorcycle-specific)
 *   avoidBadSurfaces 0=allow rough .. 1=strongly avoid unpaved
 *   shortest        true = minimize distance, ignore time
 * In every mode the `motorcycle` costing still honors OSM access tags, so it won't route onto a
 * road tagged motorcycle=no (e.g. a bike-banned expressway) regardless of preset.
 */
enum class RideMode(
    val label: String,
    val useHighways: Double,
    val useTrails: Double,
    val avoidBadSurfaces: Double,
    val shortest: Boolean,
) {
    FASTEST("Fastest", useHighways = 0.9, useTrails = 0.0, avoidBadSurfaces = 0.75, shortest = false),
    BALANCED("Balanced", useHighways = 0.5, useTrails = 0.0, avoidBadSurfaces = 0.5, shortest = false),
    AVOID_HIGHWAYS("Avoid highways", useHighways = 0.1, useTrails = 0.0, avoidBadSurfaces = 0.5, shortest = false),
    EXPLORE("Explore", useHighways = 0.3, useTrails = 0.6, avoidBadSurfaces = 0.1, shortest = false),
    SHORTEST("Shortest", useHighways = 0.5, useTrails = 0.2, avoidBadSurfaces = 0.25, shortest = true),
}

data class RouteOptions(
    val mode: RideMode = RideMode.BALANCED,
    val avoidTolls: Boolean = false,
    val avoidFerries: Boolean = false,
    val alternativeRoutes: Boolean = true,
)

/**
 * On-device routing via Valhalla. Fully offline once the tile extract is loaded.
 *
 * Tiles are pre-built from OSM data on a server (gisops/valhalla Docker image),
 * packaged as valhalla_tiles.tar, and downloaded to the device. The Valhalla
 * C++ engine (via valhalla-mobile) computes routes locally from the tar.
 *
 * Uses the motorcycle costing model. Toll/highway avoidance is applied at
 * query time via costing_options (no graph rebuild needed).
 */
class Router(private val context: Context) {

    companion object {
        private const val TAG = "Router"
        private const val TILES_DIR_NAME = "valhalla"
        // The ONE pre-assembled all-India tar the .so routes from (tile_extract, mmap'd). The loose
        // tile_dir path crashes the mobile .so; a proper tar works.
        private const val TILE_EXTRACT_NAME = "valhalla_tiles.tar"
        private const val ROUTE_TIMEOUT_MS = 8_000L
    }

    private var engine: ValhallaKotlin? = null
    private var configPath: String? = null
    private val mutex = Mutex()
    @Volatile var isReady = false
        private set

    val tilesDir: File
        get() = File(context.filesDir, TILES_DIR_NAME)

    /** The all-India routable extract the .so reads (filesDir/valhalla/valhalla_tiles.tar). */
    val tileExtractFile: File
        get() = File(tilesDir, TILE_EXTRACT_NAME)

    /** True when the routable extract exists (assembled from installed packs). */
    fun graphExists(): Boolean = tileExtractFile.exists() && tileExtractFile.length() > 0

    /**
     * Reload the graph after packs are added/removed and the extract has been rebuilt. A fresh
     * ValhallaKotlin re-mmaps the new tar. Safe to call repeatedly.
     */
    suspend fun reload(): Result<Unit> = withContext(Dispatchers.IO) {
        mutex.withLock { isReady = false; engine = null; configPath = null }
        load()
    }

    suspend fun load(): Result<Unit> = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (isReady) return@withContext Result.success(Unit)

            if (!graphExists()) {
                val msg = "Routing extract not found at ${tileExtractFile.absolutePath}"
                DebugLog.w(TAG) { msg }
                return@withContext Result.failure(IllegalStateException(msg))
            }

            try {
                configPath = ValhallaConfig.write(context, tileExtractFile.absolutePath)
                engine = ValhallaKotlin()
                isReady = true
                DebugLog.i(TAG) { "Valhalla loaded from extract ${tileExtractFile.absolutePath} (${tileExtractFile.length()/1024/1024}MB)" }
                Result.success(Unit)
            } catch (e: Exception) {
                DebugLog.e(TAG, { "Failed to load tiles: ${e.message}" }, e)
                Result.failure(e)
            }
        }
    }

    suspend fun route(
        from: GeoPoint,
        to: GeoPoint,
        options: RouteOptions = RouteOptions(),
    ): RouterResult = withContext(Dispatchers.IO) {
        val eng = engine
        val cfg = configPath
        if (eng == null || cfg == null || !isReady) {
            return@withContext RouterResult.Failure(
                RouterError.GraphNotLoaded("Valhalla not loaded — call load() first")
            )
        }

        try {
            val requestJson = buildRequest(from, to, options)
            // The native route() call is blocking and cannot be interrupted. We run it in a child
            // coroutine and abandon it via withTimeoutOrNull: the caller is freed after the timeout
            // (no request pile-up), though the orphaned native thread runs to completion in the
            // background. ROUTE_TIMEOUT_MS is generous — on-device Valhalla is normally sub-second.
            val rawResponse = coroutineScope {
                val deferred = async(Dispatchers.IO) { eng.route(requestJson, cfg) }
                withTimeoutOrNull(ROUTE_TIMEOUT_MS) { deferred.await() }
            }
            if (rawResponse == null) {
                DebugLog.w(TAG) { "Routing timed out after ${ROUTE_TIMEOUT_MS}ms" }
                return@withContext RouterResult.Failure(
                    RouterError.RoutingFailed(RuntimeException("route timeout"))
                )
            }
            val routes = parseResponse(rawResponse)
            if (routes.isEmpty()) {
                DebugLog.w(TAG) { "No route in response: ${rawResponse.take(200)}" }
                return@withContext RouterResult.Failure(RouterError.NoRouteFound(from, to))
            }
            RouterResult.Success(
                route = routes.first(),
                alternatives = routes.drop(1),
            )
        } catch (e: Exception) {
            DebugLog.e(TAG, { "Routing exception: ${e.message}" }, e)
            RouterResult.Failure(RouterError.RoutingFailed(e))
        }
    }

    fun release() {
        engine = null
        configPath = null
        isReady = false
    }

    private fun buildRequest(from: GeoPoint, to: GeoPoint, options: RouteOptions): String {
        val root = JSONObject()
        val locations = org.json.JSONArray()
        locations.put(JSONObject().put("lat", from.lat).put("lon", from.lng))
        locations.put(JSONObject().put("lat", to.lat).put("lon", to.lng))
        root.put("locations", locations)
        root.put("costing", "motorcycle")

        val costingOptions = JSONObject()
        val moto = JSONObject()
        val mode = options.mode
        moto.put("use_tolls", if (options.avoidTolls) 0.0 else 0.5)
        moto.put("use_highways", mode.useHighways)
        moto.put("use_trails", mode.useTrails)
        moto.put("avoid_bad_surfaces", mode.avoidBadSurfaces)
        if (mode.shortest) moto.put("shortest", true)
        moto.put("use_ferry", if (options.avoidFerries) 0.0 else 0.5)
        costingOptions.put("motorcycle", moto)
        root.put("costing_options", costingOptions)

        root.put("units", "kilometers")
        if (options.alternativeRoutes) root.put("alternates", 2)
        return root.toString()
    }

    private fun parseResponse(json: String): List<Route> = ValhallaTripParser.parse(json)
}

/**
 * Pure parser for a Valhalla `/route` response → [Route] list. Extracted from [Router] (no
 * Android Context / instance state) so the distance-axis correctness is unit-testable in JVM.
 */
internal object ValhallaTripParser {

    fun parse(json: String): List<Route> {
        val root = JSONObject(json)
        val routes = ArrayList<Route>()
        root.optJSONObject("trip")?.let { parseTrip(it)?.let { r -> routes.add(r) } }
        root.optJSONArray("alternates")?.let { alts ->
            for (i in 0 until alts.length()) {
                alts.getJSONObject(i).optJSONObject("trip")?.let { parseTrip(it)?.let { r -> routes.add(r) } }
            }
        }
        return routes
    }

    fun parseTrip(trip: JSONObject): Route? {
        val legs = trip.optJSONArray("legs") ?: return null
        if (legs.length() == 0) return null

        val geometry = ArrayList<GeoPoint>()
        // First pass: concatenate leg geometry and record each maneuver with its GLOBAL shape
        // index. We deliberately do NOT compute distances from Valhalla's per-maneuver `length`
        // here: NavEngine snaps the rider onto the haversine polyline, so maneuver distances
        // MUST live on that same axis. Mixing Valhalla road-length with haversine snap distance
        // was the root cause of wrong turn-by-turn distances. We assign cumulativeMeters from
        // the haversine cumulative[] array (built below) via the maneuver's shape index.
        data class RawManeuver(val type: ManeuverType, val instruction: String,
                               val beginIdx: Int, val exitCount: Int, val valhallaLengthM: Double,
                               val streetName: String)
        val raw = ArrayList<RawManeuver>()

        for (li in 0 until legs.length()) {
            val leg = legs.getJSONObject(li)
            // Valhalla shape is precision-6 encoded polyline. Decoded in native C++
            // (NativeGeo), which falls back to PolylineCodec if the .so is unavailable.
            val shape = leg.optString("shape")
            val legPoints = com.recon.dash.util.NativeGeo.decode(shape, precision = 6)
            val startIndex = geometry.size  // per-leg offset into the concatenated shape
            geometry.addAll(legPoints)

            val legManeuvers = leg.optJSONArray("maneuvers")
            if (legManeuvers != null) {
                for (mi in 0 until legManeuvers.length()) {
                    val m = legManeuvers.getJSONObject(mi)
                    // begin_shape_index is PER-LEG and inclusive — offset into the global shape.
                    val beginIdx = (startIndex + m.optInt("begin_shape_index", 0))
                        .coerceIn(0, (geometry.size - 1).coerceAtLeast(0))
                    val type = mapValhallaType(m.optInt("type", 0))
                    // Prefer the verbal instruction (concise, spoken) then the written one.
                    val instruction = m.optString("verbal_pre_transition_instruction", "")
                        .ifBlank { m.optString("instruction", "") }
                    val exitCount = m.optInt("roundabout_exit_count", 0)
                    val lengthM = m.optDouble("length", 0.0) * 1000.0
                    // street_names is an array of road names for the segment this maneuver travels.
                    val streetName = m.optJSONArray("street_names")?.let { arr ->
                        if (arr.length() > 0) arr.optString(0, "") else ""
                    } ?: ""
                    raw.add(RawManeuver(type, instruction, beginIdx, exitCount, lengthM, streetName))
                }
            }
        }

        if (geometry.size < 2) return null

        // Haversine cumulative distance at each shape vertex — the single distance axis.
        val cumulative = DoubleArray(geometry.size)
        for (i in 1 until geometry.size) {
            cumulative[i] = cumulative[i - 1] + GeoPoint.distMeters(geometry[i - 1], geometry[i])
        }

        // Second pass: place each maneuver on the haversine axis via its shape index, so
        // NavEngine's `maneuver.cumulativeMeters - bestCum` distance-to-turn is correct.
        val maneuvers = raw.map { rm ->
            Maneuver(
                type = rm.type,
                instruction = rm.instruction,
                location = geometry[rm.beginIdx.coerceIn(0, geometry.size - 1)],
                cumulativeMeters = cumulative[rm.beginIdx.coerceIn(0, cumulative.size - 1)],
                roundaboutExitCount = rm.exitCount,
                valhallaLengthM = rm.valhallaLengthM,
                streetName = rm.streetName,
            )
        }

        val summary = trip.optJSONObject("summary")
        val totalSeconds = summary?.optDouble("time", 0.0) ?: 0.0
        // Use the haversine cumulative total (same axis as snapping) so remaining-distance and
        // arrival math are consistent. Valhalla's summary.length is road-distance (different axis).
        val totalMeters = cumulative.last()

        return Route(
            geometry = geometry,
            maneuvers = maneuvers,
            totalMeters = totalMeters,
            totalSeconds = totalSeconds,
            cumulative = cumulative,
        )
    }

    /**
     * Map the Valhalla maneuver `type` integer (verified enum 0-43) to our ManeuverType.
     * Grouping follows Valhalla's own OSRM serializer turn_modifier logic:
     *   slight-right group = kSlightRight/kStayRight/kExitRight/kMergeRight
     *   slight-left group  = kSlightLeft/kStayLeft/kExitLeft/kMergeLeft
     */
    private fun mapValhallaType(type: Int): ManeuverType = when (type) {
        1, 2, 3 -> ManeuverType.DEPART                 // kStart / kStartRight / kStartLeft
        4, 5, 6 -> ManeuverType.ARRIVE                 // kDestination variants
        7, 8 -> ManeuverType.CONTINUE                  // kBecomes / kContinue
        9 -> ManeuverType.SLIGHT_RIGHT                 // kSlightRight
        10 -> ManeuverType.TURN_RIGHT                  // kRight
        11 -> ManeuverType.SHARP_RIGHT                 // kSharpRight
        12, 13 -> ManeuverType.UTURN                   // kUturnRight / kUturnLeft
        14 -> ManeuverType.SHARP_LEFT                  // kSharpLeft
        15 -> ManeuverType.TURN_LEFT                   // kLeft
        16 -> ManeuverType.SLIGHT_LEFT                 // kSlightLeft
        17 -> ManeuverType.CONTINUE                    // kRampStraight
        18 -> ManeuverType.KEEP_RIGHT                  // kRampRight  (fork onto ramp, not a slight turn)
        19 -> ManeuverType.KEEP_LEFT                   // kRampLeft
        20 -> ManeuverType.KEEP_RIGHT                  // kExitRight
        21 -> ManeuverType.KEEP_LEFT                   // kExitLeft
        22 -> ManeuverType.CONTINUE                    // kStayStraight
        23 -> ManeuverType.KEEP_RIGHT                  // kStayRight  (bear/keep right)
        24 -> ManeuverType.KEEP_LEFT                   // kStayLeft   (bear/keep left)
        25 -> ManeuverType.CONTINUE                    // kMerge (straight)
        26, 27 -> ManeuverType.ROUNDABOUT              // kRoundaboutEnter / kRoundaboutExit
        // 28,29 = ferry enter/exit; 30-36 = transit; 39-43 = elevator/steps/escalator/building
        37 -> ManeuverType.KEEP_RIGHT                  // kMergeRight
        38 -> ManeuverType.KEEP_LEFT                   // kMergeLeft
        else -> ManeuverType.CONTINUE
    }
}
