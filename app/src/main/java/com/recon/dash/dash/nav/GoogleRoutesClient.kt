package com.recon.dash.dash.nav

import com.recon.dash.util.DebugLog
import com.recon.dash.util.NativeGeo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Google Routes API (Directions v2 `computeRoutes`) client — used ONLY to capture a
 * reference route for offline divergence analysis against on-device Valhalla. It is NOT in
 * the live navigation path: the shipped app never calls this (guarded at the call site by
 * BuildConfig.DEBUG). The goal is a tuning dataset — "where does Valhalla pick a different
 * road than Google?" — that we read offline to tune Valhalla's costing. No ML, no runtime
 * decision reads the comparison.
 *
 * Auth reuses the same GCP API key as Places (BuildConfig.GOOGLE_PLACES_KEY); the Routes API
 * just needs enabling on that project. Set [apiKey] once at startup (see ReconDashApp).
 *
 * Mirrors [OsrmClient]: raw HttpURLConnection + org.json, decodes to the shared [Route] type
 * on the same haversine cumulative axis so it drops straight into [RouteComparator].
 */
object GoogleRoutesClient {
    private const val TAG = "GoogleRoutes"
    private const val URL_STR = "https://routes.googleapis.com/directions/v2:computeRoutes"
    // Field mask keeps the response tiny — we only need geometry + distance + duration.
    private const val FIELD_MASK =
        "routes.polyline.encodedPolyline,routes.distanceMeters,routes.duration"
    private const val TIMEOUT_MS = 10_000

    /** Same key as Places; empty until ReconDashApp injects it. */
    var apiKey: String = ""

    /** True when a key is present — callers should skip capture entirely if not. */
    val isConfigured: Boolean get() = apiKey.isNotBlank()

    suspend fun route(from: GeoPoint, to: GeoPoint): RouterResult = withContext(Dispatchers.IO) {
        if (!isConfigured) {
            return@withContext RouterResult.Failure(
                RouterError.RoutingFailed(IllegalStateException("No Google API key configured"))
            )
        }
        try {
            val body = buildRequest(from, to)
            val conn = (URL(URL_STR).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("X-Goog-Api-Key", apiKey)
                setRequestProperty("X-Goog-FieldMask", FIELD_MASK)
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            conn.disconnect()
            if (code !in 200..299) {
                DebugLog.w(TAG) { "Routes API HTTP $code: ${text.take(300)}" }
                return@withContext RouterResult.Failure(
                    RouterError.RoutingFailed(RuntimeException("HTTP $code"))
                )
            }
            parse(text)
        } catch (e: Exception) {
            DebugLog.w(TAG) { "Google route failed: ${e.message}" }
            RouterResult.Failure(RouterError.RoutingFailed(e))
        }
    }

    private fun buildRequest(from: GeoPoint, to: GeoPoint): String {
        fun waypoint(p: GeoPoint) = JSONObject().put(
            "location",
            JSONObject().put(
                "latLng",
                JSONObject().put("latitude", p.lat).put("longitude", p.lng),
            ),
        )
        return JSONObject().apply {
            put("origin", waypoint(from))
            put("destination", waypoint(to))
            put("travelMode", "TWO_WHEELER")           // closest Google mode to a motorcycle
            // TRAFFIC_UNAWARE on purpose: Valhalla has NO live traffic, so we compare pure
            // road-network choice (what we're tuning). Traffic-adjusted routes would make Google
            // divert around jams and we'd misread that as a road-preference difference. Also the
            // cheaper SKU.
            put("routingPreference", "TRAFFIC_UNAWARE")
            put("polylineEncoding", "ENCODED_POLYLINE") // precision-5, same as OSRM
        }.toString()
    }

    /** Parse a computeRoutes response into our [Route] on the haversine cumulative axis. */
    internal fun parse(json: String): RouterResult {
        val root = JSONObject(json)
        val routes: JSONArray = root.optJSONArray("routes") ?: JSONArray()
        if (routes.length() == 0) {
            return RouterResult.Failure(RouterError.NoRouteFound(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.0)))
        }
        val out = ArrayList<Route>()
        for (i in 0 until routes.length()) {
            val r = routes.getJSONObject(i)
            val encoded = r.optJSONObject("polyline")?.optString("encodedPolyline").orEmpty()
            val geometry = NativeGeo.decode(encoded, precision = 5)
            if (geometry.size < 2) continue
            val cum = DoubleArray(geometry.size)
            for (k in 1 until geometry.size) {
                cum[k] = cum[k - 1] + GeoPoint.distMeters(geometry[k - 1], geometry[k])
            }
            // duration comes as e.g. "1234s"; distanceMeters is an int.
            val durationSeconds = r.optString("duration").removeSuffix("s").toDoubleOrNull() ?: 0.0
            val distanceMeters = r.optDouble("distanceMeters", cum.last())
            // No maneuvers requested (field mask) — comparison only needs geometry + totals.
            out.add(
                Route(
                    geometry = geometry,
                    maneuvers = emptyList(),
                    totalMeters = distanceMeters,
                    totalSeconds = durationSeconds,
                    cumulative = cum,
                )
            )
        }
        if (out.isEmpty()) {
            return RouterResult.Failure(RouterError.NoRouteFound(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.0)))
        }
        return RouterResult.Success(route = out.first(), alternatives = out.drop(1))
    }
}
