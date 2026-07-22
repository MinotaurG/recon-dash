package com.recon.dash.search

import android.content.Context
import com.recon.dash.dash.nav.GeoPoint
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists the rider's recently selected search destinations (most recent first,
 * capped at [MAX]). Shown when the search bar is opened with an empty query.
 */
class RecentSearchStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("recent_searches", Context.MODE_PRIVATE)

    fun get(): List<SearchResult> {
        val raw = prefs.getString(KEY, "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                SearchResult(
                    name = o.getString("name"),
                    address = o.optString("address"),
                    location = GeoPoint(o.getDouble("lat"), o.getDouble("lng")),
                    type = o.optString("type"),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun add(result: SearchResult) {
        val current = get().toMutableList()
        // De-dup by name+coords, move to front.
        current.removeAll { it.name == result.name && it.location.lat == result.location.lat }
        current.add(0, result)
        val trimmed = current.take(MAX)
        val arr = JSONArray()
        trimmed.forEach { r ->
            arr.put(JSONObject().apply {
                put("name", r.name)
                put("address", r.address)
                put("lat", r.location.lat)
                put("lng", r.location.lng)
                put("type", r.type)
            })
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    companion object {
        private const val KEY = "recents"
        private const val MAX = 8
    }
}
