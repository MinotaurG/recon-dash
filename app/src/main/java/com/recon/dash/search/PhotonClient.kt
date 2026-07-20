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
 * Photon geocoding API client. Free, OSM-based, autocomplete-friendly.
 * No API key required. Results are filtered to India by default.
 */
object PhotonClient {
    private const val TAG = "PhotonClient"
    private const val BASE_URL = "https://photon.komoot.io/api/"
    private const val LIMIT = 8
    private const val TIMEOUT_MS = 8_000

    suspend fun search(
        query: String,
        biasLat: Double? = null,
        biasLng: Double? = null,
    ): SearchOutcome = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.length < 2) return@withContext SearchOutcome.Failure(SearchError.EmptyQuery)

        val params = buildString {
            append("q=").append(URLEncoder.encode(trimmed, "UTF-8"))
            append("&limit=").append(LIMIT)
            append("&lang=en")
            if (biasLat != null && biasLng != null) {
                append("&lat=").append(biasLat)
                append("&lon=").append(biasLng)
            }
        }

        try {
            val conn = (URL("$BASE_URL?$params").openConnection() as HttpURLConnection).apply {
                setRequestProperty("User-Agent", "ReconDash/1.0 (motorcycle-nav)")
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
            }
            val body = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            conn.disconnect()
            val results = parse(body)
            SearchOutcome.Success(results)
        } catch (e: Exception) {
            DebugLog.w(TAG) { "Search failed: ${e.message}" }
            SearchOutcome.Failure(SearchError.NetworkFailed(e))
        }
    }

    private fun parse(json: String): List<SearchResult> {
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
