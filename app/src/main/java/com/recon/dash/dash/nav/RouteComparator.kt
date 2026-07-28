package com.recon.dash.dash.nav

/**
 * Result of comparing an on-device Valhalla route against a Google reference route for the same
 * origin→destination. This is a diagnostic/tuning signal, NOT a runtime navigation input.
 *
 * @param overlapPct        fraction [0,1] of Valhalla's geometry that lies within [corridorM] of
 *                          Google's route. 1.0 = identical roads; low = they chose different roads.
 * @param deltaMeters       valhalla.totalMeters − google.totalMeters (positive = Valhalla longer).
 * @param deltaSeconds      valhalla.totalSeconds − google.totalSeconds (positive = Valhalla slower).
 * @param valhallaMeters    for the persisted record.
 * @param googleMeters      for the persisted record.
 */
data class RouteDivergence(
    val overlapPct: Double,
    val deltaMeters: Double,
    val deltaSeconds: Double,
    val valhallaMeters: Double,
    val googleMeters: Double,
    val valhallaSeconds: Double,
    val googleSeconds: Double,
)

/**
 * Pure geometry comparison of two routes. No Android, no I/O — unit-tested in JVM like NavEngine.
 *
 * "Overlap" is directional: we ask how much of the Valhalla route runs along Google's chosen
 * roads. We sample Valhalla's polyline at a fixed spacing (so long straight segments don't
 * dominate) and count the fraction of samples whose perpendicular distance to the NEAREST
 * segment of Google's polyline is within a corridor. That distance-based corridor tolerates GPS
 * -grade geometry differences while still flagging genuinely different roads.
 */
object RouteComparator {

    private const val CORRIDOR_M = 25.0     // within this of Google's line = "same road"
    private const val SAMPLE_SPACING_M = 20.0

    fun compare(valhalla: Route, google: Route): RouteDivergence {
        val overlap = overlapFraction(valhalla.geometry, google.geometry)
        return RouteDivergence(
            overlapPct = overlap,
            deltaMeters = valhalla.totalMeters - google.totalMeters,
            deltaSeconds = valhalla.totalSeconds - google.totalSeconds,
            valhallaMeters = valhalla.totalMeters,
            googleMeters = google.totalMeters,
            valhallaSeconds = valhalla.totalSeconds,
            googleSeconds = google.totalSeconds,
        )
    }

    /** Fraction of [subject] (sampled by arc length) lying within [CORRIDOR_M] of [reference]. */
    private fun overlapFraction(subject: List<GeoPoint>, reference: List<GeoPoint>): Double {
        if (subject.size < 2 || reference.size < 2) return 0.0
        val samples = sampleByDistance(subject, SAMPLE_SPACING_M)
        if (samples.isEmpty()) return 0.0
        var near = 0
        for (s in samples) {
            if (distToPolyline(s, reference) <= CORRIDOR_M) near++
        }
        return near.toDouble() / samples.size
    }

    /** Resample a polyline to points spaced ~[spacingM] apart along its length (endpoints kept). */
    private fun sampleByDistance(pts: List<GeoPoint>, spacingM: Double): List<GeoPoint> {
        val out = ArrayList<GeoPoint>()
        out.add(pts.first())
        var carry = 0.0
        for (i in 1 until pts.size) {
            val a = pts[i - 1]; val b = pts[i]
            val segLen = GeoPoint.distMeters(a, b)
            if (segLen == 0.0) continue
            var d = spacingM - carry
            while (d < segLen) {
                val t = d / segLen
                out.add(GeoPoint(a.lat + (b.lat - a.lat) * t, a.lng + (b.lng - a.lng) * t))
                d += spacingM
            }
            carry = (carry + segLen) % spacingM
        }
        out.add(pts.last())
        return out
    }

    /** Minimum perpendicular distance (m) from [p] to any segment of [line]. */
    private fun distToPolyline(p: GeoPoint, line: List<GeoPoint>): Double {
        var best = Double.MAX_VALUE
        for (i in 0 until line.size - 1) {
            val (proj, _) = GeoPoint.projectOnSegment(p, line[i], line[i + 1])
            val d = GeoPoint.distMeters(p, proj)
            if (d < best) best = d
        }
        return best
    }
}
