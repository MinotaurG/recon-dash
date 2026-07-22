package com.recon.dash.search

import com.recon.dash.dash.nav.GeoPoint
import com.recon.dash.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class SearchResult(
    val name: String,
    val address: String,
    val location: GeoPoint,
    val type: String,
)

sealed class SearchError {
    data class NetworkFailed(val cause: Throwable) : SearchError()
    data class ParseFailed(val cause: Throwable) : SearchError()
    object EmptyQuery : SearchError()
}

sealed class SearchOutcome {
    data class Success(val results: List<SearchResult>) : SearchOutcome()
    data class Failure(val error: SearchError) : SearchOutcome()
}

/**
 * Unified search: tries Photon (free, OSM) first, falls back to
 * Google Places Autocomplete if Photon returns empty. This gives
 * good coverage for Indian local businesses that aren't in OSM.
 */
object PhotonClient {
    private const val TAG = "PhotonClient"
    private const val PHOTON_URL = "https://photon.komoot.io/api/"
    private const val GOOGLE_URL = "https://places.googleapis.com/v1/places:searchText"
    private const val LIMIT = 8
    private const val TIMEOUT_MS = 8_000
    private const val LOCAL_RADIUS_KM = 150.0

    var googleApiKey: String = ""

    suspend fun search(
        query: String,
        biasLat: Double? = null,
        biasLng: Double? = null,
    ): SearchOutcome = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.length < 2) return@withContext SearchOutcome.Failure(SearchError.EmptyQuery)

        // Google Places is far better at named businesses/POIs (which is what riders
        // search for). Use it as the PRIMARY source when a key is configured; Photon
        // is the offline / no-key fallback. This avoids Photon's fuzzy junk
        // ("Top Paints" for "topshot") and gives Google-Maps-quality results.
        if (googleApiKey.isNotBlank()) {
            val googleResult = searchGoogle(trimmed, biasLat, biasLng)
            if (googleResult is SearchOutcome.Success && googleResult.results.isNotEmpty()) {
                return@withContext googleResult
            }
        }

        // Fallback to Photon (offline geocoding / no key / Google returned nothing).
        searchPhoton(trimmed, biasLat, biasLng)
    }

    private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }

    private fun searchPhoton(
        query: String,
        biasLat: Double?,
        biasLng: Double?,
    ): SearchOutcome {
        val params = buildString {
            append("q=").append(URLEncoder.encode(query, "UTF-8"))
            append("&limit=").append(LIMIT)
            append("&lang=en")
            if (biasLat != null && biasLng != null) {
                append("&lat=").append(biasLat)
                append("&lon=").append(biasLng)
            }
        }

        return try {
            val conn = (URL("$PHOTON_URL?$params").openConnection() as HttpURLConnection).apply {
                setRequestProperty("User-Agent", "ReconDash/1.0 (motorcycle-nav)")
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
            }
            val body = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            conn.disconnect()
            SearchOutcome.Success(parsePhoton(body))
        } catch (e: Exception) {
            DebugLog.w(TAG) { "Photon search failed: ${e.message}" }
            SearchOutcome.Failure(SearchError.NetworkFailed(e))
        }
    }

    private fun searchGoogle(
        query: String,
        biasLat: Double?,
        biasLng: Double?,
    ): SearchOutcome {
        return try {
            val requestBody = buildGoogleRequestBody(query, biasLat, biasLng)
            val conn = (URL(GOOGLE_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("X-Goog-Api-Key", googleApiKey)
                setRequestProperty("X-Goog-FieldMask", "places.displayName,places.formattedAddress,places.location")
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                doOutput = true
            }
            conn.outputStream.use { it.write(requestBody.toByteArray(Charsets.UTF_8)) }
            val body = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            conn.disconnect()
            val results = parseGoogleTextSearch(body)
            DebugLog.i(TAG) { "Google fallback: ${results.size} results for '$query'" }
            SearchOutcome.Success(results)
        } catch (e: Exception) {
            DebugLog.w(TAG) { "Google search failed: ${e.message}" }
            SearchOutcome.Failure(SearchError.NetworkFailed(e))
        }
    }

    private fun buildGoogleRequestBody(query: String, biasLat: Double?, biasLng: Double?): String {
        val body = JSONObject()
        body.put("textQuery", query)
        body.put("maxResultCount", LIMIT)
        if (biasLat != null && biasLng != null) {
            val locationBias = JSONObject()
            val circle = JSONObject()
            val center = JSONObject()
            center.put("latitude", biasLat)
            center.put("longitude", biasLng)
            circle.put("center", center)
            circle.put("radius", 50000.0)
            locationBias.put("circle", circle)
            body.put("locationBias", locationBias)
        }
        return body.toString()
    }

    private fun parseGoogleTextSearch(json: String): List<SearchResult> {
        val root = JSONObject(json)
        val places = root.optJSONArray("places") ?: return emptyList()
        val results = ArrayList<SearchResult>(places.length())
        for (i in 0 until places.length()) {
            val place = places.getJSONObject(i)
            val loc = place.optJSONObject("location") ?: continue
            val name = place.optJSONObject("displayName")?.optString("text", "") ?: ""
            val address = place.optString("formattedAddress", "")
            if (name.isBlank()) continue
            results.add(SearchResult(
                name = name,
                address = address,
                location = GeoPoint(loc.getDouble("latitude"), loc.getDouble("longitude")),
                type = "google",
            ))
        }
        return results
    }

    private fun parsePhoton(json: String): List<SearchResult> {
        val root = JSONObject(json)
        val features = root.optJSONArray("features") ?: return emptyList()
        val results = ArrayList<SearchResult>(features.length())

        for (i in 0 until features.length()) {
            val feature = features.getJSONObject(i)
            val props = feature.optJSONObject("properties") ?: continue
            val geom = feature.optJSONObject("geometry") ?: continue
            val coords = geom.optJSONArray("coordinates") ?: continue
            if (coords.length() < 2) continue

            val lng = coords.getDouble(0)
            val lat = coords.getDouble(1)
            val name = props.optString("name").ifBlank {
                props.optString("street", "Unknown")
            }
            val address = buildAddress(props)
            val type = props.optString("osm_value", props.optString("type", ""))

            results.add(SearchResult(
                name = name,
                address = address,
                location = GeoPoint(lat, lng),
                type = type,
            ))
        }
        return results
    }

    private fun buildAddress(props: JSONObject): String {
        val parts = listOfNotNull(
            props.optString("street").takeIf { it.isNotBlank() },
            props.optString("city").takeIf { it.isNotBlank() },
            props.optString("state").takeIf { it.isNotBlank() },
            props.optString("country").takeIf { it.isNotBlank() },
        )
        return parts.joinToString(", ")
    }
}
