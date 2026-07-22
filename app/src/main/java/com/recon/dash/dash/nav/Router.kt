package com.recon.dash.dash.nav

import android.content.Context
import com.recon.dash.util.DebugLog
import com.valhalla.valhalla.ValhallaConfig
import com.valhalla.valhalla.ValhallaKotlin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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

data class RouteOptions(
    val avoidTolls: Boolean = false,
    val avoidHighways: Boolean = false,
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
        private const val TILES_FILE_NAME = "valhalla_tiles.tar"
    }

    private var engine: ValhallaKotlin? = null
    private var configPath: String? = null
    private val mutex = Mutex()
    @Volatile var isReady = false
        private set

    val tilesDir: File
        get() = File(context.filesDir, TILES_DIR_NAME)

    private val tilesFile: File
        get() = File(tilesDir, TILES_FILE_NAME)

    fun graphExists(): Boolean = tilesFile.exists() && tilesFile.length() > 0

    suspend fun load(): Result<Unit> = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (isReady) return@withContext Result.success(Unit)

            if (!graphExists()) {
                val msg = "Tiles not found at ${tilesFile.absolutePath}"
                DebugLog.w(TAG) { msg }
                return@withContext Result.failure(IllegalStateException(msg))
            }

            try {
                configPath = ValhallaConfig.write(context, tilesFile.absolutePath)
                engine = ValhallaKotlin()
                isReady = true
                DebugLog.i(TAG) { "Valhalla loaded from ${tilesFile.absolutePath}" }
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
            val rawResponse = eng.route(requestJson, cfg)
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
        moto.put("use_tolls", if (options.avoidTolls) 0.0 else 0.5)
        moto.put("use_highways", if (options.avoidHighways) 0.1 else 0.5)
        costingOptions.put("motorcycle", moto)
        root.put("costing_options", costingOptions)

        root.put("units", "kilometers")
        if (options.alternativeRoutes) root.put("alternates", 2)
        return root.toString()
    }

    private fun parseResponse(json: String): List<Route> {
        val root = JSONObject(json)
        val routes = ArrayList<Route>()

        // Primary trip
        root.optJSONObject("trip")?.let { parseTrip(it)?.let { r -> routes.add(r) } }
        // Alternates
        root.optJSONArray("alternates")?.let { alts ->
            for (i in 0 until alts.length()) {
                alts.getJSONObject(i).optJSONObject("trip")?.let { parseTrip(it)?.let { r -> routes.add(r) } }
            }
        }
        return routes
    }

    private fun parseTrip(trip: JSONObject): Route? {
        val legs = trip.optJSONArray("legs") ?: return null
        if (legs.length() == 0) return null

        val geometry = ArrayList<GeoPoint>()
        val maneuvers = ArrayList<Maneuver>()
        var cumulativeOffset = 0.0

        for (li in 0 until legs.length()) {
            val leg = legs.getJSONObject(li)
            // Valhalla shape is precision-6 encoded polyline. Decoded in native C++
            // (NativeGeo), which falls back to PolylineCodec if the .so is unavailable.
            val shape = leg.optString("shape")
            val legPoints = com.recon.dash.util.NativeGeo.decode(shape, precision = 6)
            val startIndex = geometry.size
            geometry.addAll(legPoints)

            val legManeuvers = leg.optJSONArray("maneuvers")
            if (legManeuvers != null) {
                for (mi in 0 until legManeuvers.length()) {
                    val m = legManeuvers.getJSONObject(mi)
                    val beginIdx = m.optInt("begin_shape_index", 0)
                    val globalIdx = (startIndex + beginIdx).coerceIn(0, geometry.size - 1)
                    val loc = geometry.getOrNull(globalIdx) ?: continue
                    val type = mapValhallaType(m.optInt("type", 0))
                    val instruction = m.optString("instruction", "")
                    val cumFromStart = cumulativeOffset + geometry.take(globalIdx + 1).let { pts ->
                        var d = 0.0
                        for (i in startIndex + 1..globalIdx) d += GeoPoint.distMeters(geometry[i - 1], geometry[i])
                        d
                    }
                    maneuvers.add(
                        Maneuver(
                            type = type,
                            instruction = instruction,
                            location = loc,
                            cumulativeMeters = cumFromStart,
                        )
                    )
                }
            }
        }

        if (geometry.size < 2) return null

        val cumulative = DoubleArray(geometry.size)
        for (i in 1 until geometry.size) {
            cumulative[i] = cumulative[i - 1] + GeoPoint.distMeters(geometry[i - 1], geometry[i])
        }

        val summary = trip.optJSONObject("summary")
        val totalMeters = (summary?.optDouble("length", 0.0) ?: 0.0) * 1000.0 // km → m
        val totalSeconds = summary?.optDouble("time", 0.0) ?: 0.0

        return Route(
            geometry = geometry,
            maneuvers = maneuvers,
            totalMeters = if (totalMeters > 0) totalMeters else cumulative.last(),
            totalSeconds = totalSeconds,
            cumulative = cumulative,
        )
    }

    /** Map Valhalla maneuver type (0-43) to our ManeuverType enum. */
    private fun mapValhallaType(type: Int): ManeuverType = when (type) {
        1 -> ManeuverType.DEPART           // kStart
        2 -> ManeuverType.DEPART           // kStartRight
        3 -> ManeuverType.DEPART           // kStartLeft
        4, 5, 6 -> ManeuverType.ARRIVE     // kDestination variants
        9 -> ManeuverType.SLIGHT_RIGHT     // kSlightRight
        10 -> ManeuverType.TURN_RIGHT      // kRight
        11 -> ManeuverType.SHARP_RIGHT     // kSharpRight
        12 -> ManeuverType.UTURN           // kUturnRight
        13 -> ManeuverType.UTURN           // kUturnLeft
        14 -> ManeuverType.SHARP_LEFT      // kSharpLeft
        15 -> ManeuverType.TURN_LEFT       // kLeft
        16 -> ManeuverType.SLIGHT_LEFT     // kSlightLeft
        17 -> ManeuverType.CONTINUE        // kRampStraight
        18, 19 -> ManeuverType.SLIGHT_RIGHT // kRampRight
        20, 21 -> ManeuverType.SLIGHT_LEFT  // kRampLeft
        26, 27, 28, 29, 30, 31, 32, 33 -> ManeuverType.ROUNDABOUT // roundabout variants
        else -> ManeuverType.CONTINUE
    }
}
