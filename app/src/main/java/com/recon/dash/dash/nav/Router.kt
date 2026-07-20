package com.recon.dash.dash.nav

import android.content.Context
import com.graphhopper.GHRequest
import com.graphhopper.GraphHopper
import com.graphhopper.util.Instruction
import com.recon.dash.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

sealed class RouterError {
    data class GraphNotLoaded(val message: String) : RouterError()
    data class NoRouteFound(val from: GeoPoint, val to: GeoPoint) : RouterError()
    data class RoutingFailed(val cause: Throwable) : RouterError()
}

sealed class RouterResult {
    data class Success(val route: Route) : RouterResult()
    data class Failure(val error: RouterError) : RouterResult()
}

/**
 * On-device routing via GraphHopper. Fully offline once the graph is loaded.
 *
 * Graph files are pre-built from OSM data and stored on-device. The rider
 * downloads their state/region graph once; all subsequent routing is local
 * with sub-50ms latency (Contraction Hierarchies).
 */
class Router(private val context: Context) {

    companion object {
        private const val TAG = "Router"
        private const val PROFILE_MOTORCYCLE = "motorcycle"
        private const val GRAPH_DIR_NAME = "graphhopper"
    }

    private var hopper: GraphHopper? = null
    private val mutex = Mutex()
    @Volatile var isReady = false
        private set

    val graphDir: File
        get() = File(context.filesDir, GRAPH_DIR_NAME)

    fun graphExists(): Boolean = graphDir.exists() &&
        File(graphDir, "properties").exists()

    suspend fun load(): Result<Unit> = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (isReady) return@withContext Result.success(Unit)

            if (!graphExists()) {
                val msg = "Graph not found at ${graphDir.absolutePath}"
                DebugLog.w(TAG) { msg }
                return@withContext Result.failure(IllegalStateException(msg))
            }

            try {
                val gh = GraphHopper()
                gh.setGraphHopperLocation(graphDir.absolutePath)
                gh.setAllowWrites(false)
                gh.load()
                hopper = gh
                isReady = true
                DebugLog.i(TAG) { "GraphHopper loaded from ${graphDir.absolutePath}" }
                Result.success(Unit)
            } catch (e: Exception) {
                DebugLog.e(TAG, { "Failed to load graph: ${e.message}" }, e)
                Result.failure(e)
            }
        }
    }

    suspend fun route(from: GeoPoint, to: GeoPoint): RouterResult = withContext(Dispatchers.IO) {
        val gh = hopper
        if (gh == null || !isReady) {
            return@withContext RouterResult.Failure(
                RouterError.GraphNotLoaded("GraphHopper not loaded — call load() first")
            )
        }

        try {
            val request = GHRequest(from.lat, from.lng, to.lat, to.lng)
                .setProfile(PROFILE_MOTORCYCLE)

            val response = gh.route(request)

            if (response.hasErrors()) {
                val msg = response.errors.joinToString { it.message.orEmpty() }
                DebugLog.w(TAG) { "Routing failed: $msg" }
                return@withContext RouterResult.Failure(RouterError.NoRouteFound(from, to))
            }

            val best = response.best
            val points = best.points
            val numPoints = points.size()
            val geometry = ArrayList<GeoPoint>(numPoints)
            for (i in 0 until numPoints) {
                geometry.add(GeoPoint(points.getLat(i), points.getLon(i)))
            }
            if (geometry.size < 2) {
                return@withContext RouterResult.Failure(RouterError.NoRouteFound(from, to))
            }

            val cumulative = buildCumulative(geometry)
            val maneuvers = extractManeuvers(best.instructions, geometry, cumulative)

            RouterResult.Success(
                Route(
                    geometry = geometry,
                    maneuvers = maneuvers,
                    totalMeters = best.distance,
                    totalSeconds = best.time / 1000.0,
                    cumulative = cumulative,
                )
            )
        } catch (e: Exception) {
            DebugLog.e(TAG, { "Routing exception: ${e.message}" }, e)
            RouterResult.Failure(RouterError.RoutingFailed(e))
        }
    }

    fun release() {
        hopper?.close()
        hopper = null
        isReady = false
    }

    private fun buildCumulative(geometry: List<GeoPoint>): DoubleArray {
        val cum = DoubleArray(geometry.size)
        for (i in 1 until geometry.size) {
            cum[i] = cum[i - 1] + GeoPoint.distMeters(geometry[i - 1], geometry[i])
        }
        return cum
    }

    private fun extractManeuvers(
        instructions: com.graphhopper.util.InstructionList,
        geometry: List<GeoPoint>,
        cumulative: DoubleArray,
    ): List<Maneuver> {
        val maneuvers = ArrayList<Maneuver>()
        for (instr in instructions) {
            val pts = instr.points
            if (pts.size() == 0) continue
            val loc = GeoPoint(pts.getLat(0), pts.getLon(0))
            val type = mapInstructionSign(instr.sign)
            val name = instr.name.ifBlank { "road" }
            maneuvers.add(
                Maneuver(
                    type = type,
                    instruction = buildInstruction(type, name),
                    location = loc,
                    cumulativeMeters = nearestCumulative(loc, geometry, cumulative),
                )
            )
        }
        return maneuvers
    }

    private fun mapInstructionSign(sign: Int): ManeuverType = when (sign) {
        Instruction.TURN_LEFT -> ManeuverType.TURN_LEFT
        Instruction.TURN_RIGHT -> ManeuverType.TURN_RIGHT
        Instruction.TURN_SLIGHT_LEFT -> ManeuverType.SLIGHT_LEFT
        Instruction.TURN_SLIGHT_RIGHT -> ManeuverType.SLIGHT_RIGHT
        Instruction.TURN_SHARP_LEFT -> ManeuverType.SHARP_LEFT
        Instruction.TURN_SHARP_RIGHT -> ManeuverType.SHARP_RIGHT
        Instruction.U_TURN_LEFT, Instruction.U_TURN_RIGHT, Instruction.U_TURN_UNKNOWN -> ManeuverType.UTURN
        Instruction.USE_ROUNDABOUT -> ManeuverType.ROUNDABOUT
        Instruction.FINISH -> ManeuverType.ARRIVE
        Instruction.LEAVE_ROUNDABOUT -> ManeuverType.CONTINUE
        else -> ManeuverType.CONTINUE
    }

    private fun buildInstruction(type: ManeuverType, road: String): String = when (type) {
        ManeuverType.DEPART -> "Head out on $road"
        ManeuverType.ARRIVE -> "Arrive at destination"
        ManeuverType.TURN_LEFT -> "Turn left onto $road"
        ManeuverType.TURN_RIGHT -> "Turn right onto $road"
        ManeuverType.SLIGHT_LEFT -> "Slight left onto $road"
        ManeuverType.SLIGHT_RIGHT -> "Slight right onto $road"
        ManeuverType.SHARP_LEFT -> "Sharp left onto $road"
        ManeuverType.SHARP_RIGHT -> "Sharp right onto $road"
        ManeuverType.UTURN -> "Make a U-turn"
        ManeuverType.ROUNDABOUT -> "At the roundabout, take $road"
        ManeuverType.CONTINUE -> "Continue on $road"
    }

    private fun nearestCumulative(p: GeoPoint, geom: List<GeoPoint>, cum: DoubleArray): Double {
        var best = 0.0
        var bestD = Double.MAX_VALUE
        for (i in geom.indices) {
            val d = GeoPoint.distMeters(p, geom[i])
            if (d < bestD) { bestD = d; best = cum[i] }
        }
        return best
    }
}
