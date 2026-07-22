package com.recon.dash.dash.nav

import com.recon.dash.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Online routing fallback via OSRM public demo server.
 * Used when no on-device Valhalla routing tiles are installed.
 * Requires internet at route-planning time; the resulting route
 * is cached locally for offline use during the ride.
 */
object OsrmClient {
    private const val TAG = "OsrmClient"
    private const val BASE = "https://router.project-osrm.org/route/v1/driving"
    private const val UA = "ReconDash/1.0 (motorcycle-nav; single user)"

    suspend fun route(from: GeoPoint, to: GeoPoint): RouterResult = withContext(Dispatchers.IO) {
        val url = "$BASE/${from.lng},${from.lat};${to.lng},${to.lat}" +
                "?overview=full&geometries=polyline&steps=true&alternatives=true"
        try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                setRequestProperty("User-Agent", UA)
                connectTimeout = 10_000
                readTimeout = 10_000
            }
            val body = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            conn.disconnect()
            parse(body)
        } catch (e: Exception) {
            DebugLog.w(TAG) { "OSRM route failed: ${e.message}" }
            RouterResult.Failure(RouterError.RoutingFailed(e))
        }
    }

    private fun parse(json: String): RouterResult {
        val root = JSONObject(json)
        if (root.optString("code") != "Ok") {
            return RouterResult.Failure(
                RouterError.NoRouteFound(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.0))
            )
        }
        val routesArr = root.optJSONArray("routes")
        if (routesArr == null || routesArr.length() == 0) {
            return RouterResult.Failure(
                RouterError.NoRouteFound(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.0))
            )
        }

        val allRoutes = ArrayList<Route>()
        for (ri in 0 until routesArr.length()) {
            val r = routesArr.getJSONObject(ri)
            // OSRM geometry is precision-5; native decode with Kotlin fallback.
            val geometry = com.recon.dash.util.NativeGeo.decode(r.getString("geometry"), precision = 5)
            if (geometry.size < 2) continue

            val cum = DoubleArray(geometry.size)
            for (i in 1 until geometry.size) {
                cum[i] = cum[i - 1] + GeoPoint.distMeters(geometry[i - 1], geometry[i])
            }

            val maneuvers = ArrayList<Maneuver>()
            val legs = r.optJSONArray("legs")
            if (legs != null) {
                for (li in 0 until legs.length()) {
                    val steps = legs.getJSONObject(li).optJSONArray("steps") ?: continue
                    for (si in 0 until steps.length()) {
                        val step = steps.getJSONObject(si)
                        val man = step.optJSONObject("maneuver") ?: continue
                        val loc = man.optJSONArray("location") ?: continue
                        val p = GeoPoint(loc.getDouble(1), loc.getDouble(0))
                        val type = ManeuverType.fromOsrm(man.optString("type"), man.optString("modifier"))
                        val name = step.optString("name").ifBlank { "road" }
                        maneuvers.add(
                            Maneuver(
                                type = type,
                                instruction = buildInstruction(type, name),
                                location = p,
                                cumulativeMeters = nearestCum(p, geometry, cum),
                            )
                        )
                    }
                }
            }

            allRoutes.add(Route(
                geometry = geometry,
                maneuvers = maneuvers,
                totalMeters = r.optDouble("distance", cum.last()),
                totalSeconds = r.optDouble("duration", 0.0),
                cumulative = cum,
            ))
        }

        if (allRoutes.isEmpty()) {
            return RouterResult.Failure(
                RouterError.NoRouteFound(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.0))
            )
        }

        return RouterResult.Success(
            route = allRoutes.first(),
            alternatives = allRoutes.drop(1),
        )
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

    private fun nearestCum(p: GeoPoint, geom: List<GeoPoint>, cum: DoubleArray): Double {
        var best = 0.0
        var bestD = Double.MAX_VALUE
        for (i in geom.indices) {
            val d = GeoPoint.distMeters(p, geom[i])
            if (d < bestD) { bestD = d; best = cum[i] }
        }
        return best
    }
}
