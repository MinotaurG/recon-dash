package com.recon.dash.util

import com.recon.dash.dash.nav.GeoPoint
import com.recon.dash.dash.nav.PolylineCodec

/**
 * Native (C++/NDK) polyline decode + Web Mercator projection.
 *
 * The hot numeric loop lives in `src/main/cpp/geo.cpp`; this is the JNI binding.
 * If the native library fails to load (e.g. an ABI we didn't build), every call
 * transparently falls back to the pure-Kotlin [PolylineCodec] so the app never
 * breaks — the native path is an optimisation, not a hard dependency.
 */
object NativeGeo {

    val isAvailable: Boolean

    init {
        isAvailable = runCatching { System.loadLibrary("recon-native") }.isSuccess
        if (!isAvailable) {
            DebugLog.w("NativeGeo") { "recon-native unavailable — using Kotlin polyline fallback" }
        }
    }

    /** Decode an encoded polyline to lat/lng points. Drop-in for [PolylineCodec.decode]. */
    fun decode(encoded: String, precision: Int = 5): List<GeoPoint> {
        if (encoded.isEmpty()) return emptyList()
        if (!isAvailable) return PolylineCodec.decode(encoded, precision)
        val flat = runCatching { decodeLatLng(encoded, precision) }.getOrNull()
            ?: return PolylineCodec.decode(encoded, precision)
        val out = ArrayList<GeoPoint>(flat.size / 2)
        var i = 0
        while (i + 1 < flat.size) {
            out.add(GeoPoint(flat[i].toDouble(), flat[i + 1].toDouble()))
            i += 2
        }
        return out
    }

    /**
     * Decode and project to absolute Web Mercator PIXELS at [zoom] in one native call.
     * Returns a flat [x0, y0, x1, y1, ...] array (pixels = fractional tile * 256), matching
     * the renderer's convention. Falls back to Kotlin decode + projection if native is off.
     */
    fun decodeAndProjectPixels(encoded: String, precision: Int, zoom: Int): FloatArray {
        if (encoded.isEmpty()) return FloatArray(0)
        if (isAvailable) {
            runCatching { decodeAndProject(encoded, precision, zoom) }.getOrNull()?.let { return it }
        }
        // Kotlin fallback mirrors geo.cpp exactly (tile * TILE_SIZE).
        val pts = PolylineCodec.decode(encoded, precision)
        val n = (1 shl zoom).toDouble()
        val ts = com.recon.dash.dash.map.Mercator.TILE_SIZE
        val out = FloatArray(pts.size * 2)
        for (idx in pts.indices) {
            val p = pts[idx]
            val xTile = (p.lng + 180.0) / 360.0 * n
            val r = Math.toRadians(p.lat)
            val yTile = (1.0 - Math.log(Math.tan(r) + 1.0 / Math.cos(r)) / Math.PI) / 2.0 * n
            out[idx * 2] = (xTile * ts).toFloat()
            out[idx * 2 + 1] = (yTile * ts).toFloat()
        }
        return out
    }

    private external fun decodeAndProject(encoded: String, precision: Int, zoom: Int): FloatArray
    private external fun decodeLatLng(encoded: String, precision: Int): FloatArray
}
