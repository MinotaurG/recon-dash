package com.recon.dash.data

import android.content.Context
import com.recon.dash.util.DebugLog
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Maps a GPS coordinate to a region id using real state boundaries (point-in-polygon), replacing
 * the old rectangular bounding-box guesser that misclassified ~15/28 states because Indian states
 * interlock and boxes overlap.
 *
 * Boundaries come from `assets/india_states.geojson` (simplified to ~280 KB; state-level accuracy
 * is all we need). NAME_1 values are normalised to our [RegionManager] ids via [NAME_TO_ID].
 * Loaded lazily on first query and cached for the process.
 */
@Singleton
class RegionGeocoder @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        private const val TAG = "RegionGeocoder"
        private const val ASSET = "india_states.geojson"

        // GeoJSON NAME_1 -> our region id. Names not shipped as regions (UTs, splits) map to their
        // parent state so a rider there still gets a sensible download suggestion.
        private val NAME_TO_ID = mapOf(
            "Andhra Pradesh" to "andhra_pradesh",
            "Arunachal Pradesh" to "arunachal",
            "Assam" to "assam",
            "Bihar" to "bihar",
            "Chhattisgarh" to "chhattisgarh",
            "Goa" to "goa",
            "Gujarat" to "gujarat",
            "Haryana" to "haryana",
            "Himachal Pradesh" to "himachal",
            "Jammu and Kashmir" to "jammu_kashmir",
            "Jharkhand" to "jharkhand",
            "Karnataka" to "karnataka",
            "Kerala" to "kerala",
            "Madhya Pradesh" to "madhya_pradesh",
            "Maharashtra" to "maharashtra",
            "Manipur" to "manipur",
            "Meghalaya" to "meghalaya",
            "Mizoram" to "mizoram",
            "Nagaland" to "nagaland",
            "Orissa" to "odisha",
            "Odisha" to "odisha",
            "Punjab" to "punjab",
            "Rajasthan" to "rajasthan",
            "Sikkim" to "sikkim",
            "Tamil Nadu" to "tamil_nadu",
            "Telangana" to "telangana",
            "Tripura" to "tripura",
            "Uttar Pradesh" to "uttar_pradesh",
            "Uttaranchal" to "uttarakhand",
            "Uttarakhand" to "uttarakhand",
            "West Bengal" to "west_bengal",
            // UTs / enclaves -> parent state (not shipped as their own regions)
            "Chandigarh" to "punjab",
            "Delhi" to "delhi_ncr",
            "Puducherry" to "tamil_nadu",
            "Dadra and Nagar Haveli" to "maharashtra",
            "Daman and Diu" to "gujarat",
        )
    }

    /** One state polygon as a list of rings; each ring is a flat [lng, lat, lng, lat, …] array. */
    private class StateShape(val regionId: String, val rings: List<DoubleArray>)

    @Volatile private var shapes: List<StateShape>? = null

    /** Region id containing (lat, lng), or null if outside all known boundaries / on load failure. */
    fun regionIdForLocation(lat: Double, lng: Double): String? {
        val s = shapes ?: load().also { shapes = it }
        for (shape in s) {
            for (ring in shape.rings) {
                if (pointInRing(lng, lat, ring)) return shape.regionId
            }
        }
        return null
    }

    private fun load(): List<StateShape> = try {
        val text = context.assets.open(ASSET).bufferedReader().use { it.readText() }
        val features = JSONObject(text).getJSONArray("features")
        val out = ArrayList<StateShape>(features.length())
        for (i in 0 until features.length()) {
            val f = features.getJSONObject(i)
            val name = f.getJSONObject("properties").optString("NAME_1")
            val regionId = NAME_TO_ID[name] ?: continue
            val geom = f.getJSONObject("geometry")
            val rings = ArrayList<DoubleArray>()
            when (geom.getString("type")) {
                "Polygon" -> addPolygon(geom.getJSONArray("coordinates"), rings)
                "MultiPolygon" -> {
                    val polys = geom.getJSONArray("coordinates")
                    for (p in 0 until polys.length()) addPolygon(polys.getJSONArray(p), rings)
                }
            }
            if (rings.isNotEmpty()) out.add(StateShape(regionId, rings))
        }
        DebugLog.i(TAG) { "Loaded ${out.size} state shapes for geocoding" }
        out
    } catch (e: Exception) {
        DebugLog.w(TAG) { "Failed to load $ASSET: ${e.message}" }
        emptyList()
    }

    /** Take a polygon's outer ring (index 0); holes are ignored — fine at state scale. */
    private fun addPolygon(polygon: org.json.JSONArray, into: MutableList<DoubleArray>) {
        if (polygon.length() == 0) return
        val ring = polygon.getJSONArray(0)
        val flat = DoubleArray(ring.length() * 2)
        for (i in 0 until ring.length()) {
            val pt = ring.getJSONArray(i)
            flat[i * 2] = pt.getDouble(0)      // lng
            flat[i * 2 + 1] = pt.getDouble(1)  // lat
        }
        into.add(flat)
    }

    /** Ray-casting point-in-polygon on a flat [lng,lat,…] ring. */
    private fun pointInRing(x: Double, y: Double, ring: DoubleArray): Boolean {
        var inside = false
        val n = ring.size / 2
        var j = n - 1
        for (i in 0 until n) {
            val xi = ring[i * 2]; val yi = ring[i * 2 + 1]
            val xj = ring[j * 2]; val yj = ring[j * 2 + 1]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) {
                inside = !inside
            }
            j = i
        }
        return inside
    }
}
