package com.recon.dash.dash.nav

import com.recon.dash.util.DebugLog
import kotlin.math.max

/**
 * Tracks the rider's progress along a single [Route]. STATEFUL and per-route: construct one
 * [NavEngine] per active route (recreate on reroute), then feed GPS fixes to [update].
 *
 * Reliability design (vs. the old stateless global-nearest-point matcher):
 *  - Monotonic progress cursor + bounded forward search window, so it can't mis-snap onto a
 *    parallel carriageway or an earlier pass on a loop/out-and-back route.
 *  - Off-route requires N consecutive off fixes (hysteresis) AND an accuracy gate, so a single
 *    noisy fix (or a coarse NETWORK-provider fix) never triggers a spurious reroute.
 *  - Arrival requires true proximity to the destination point, not merely remaining≈0.
 *  - Exposes the snapped point + a traveled/ahead split so the map can trim the line behind
 *    the rider (all consumers share this one computation via NavSessionManager).
 *
 * All distances are on the route's haversine polyline axis ([Route.cumulative]) — the SAME axis
 * [Maneuver.cumulativeMeters] is placed on, so distance-to-turn is correct.
 */
class NavEngine(private val route: Route) {

    data class Progress(
        val snapped: GeoPoint,            // GPS snapped onto the route
        val snappedIndex: Int,            // segment index [i, i+1] the snap lies on
        val routeBearing: Double,         // bearing of the route at the snap (travel-up heading)
        val traveledMeters: Double,       // distance from route start to the snap (haversine axis)
        val remainingMeters: Double,
        val distanceToManeuverM: Double,
        val nextManeuver: Maneuver?,
        val etaSeconds: Double,
        val offRoute: Boolean,
        val arrived: Boolean,
        val snapDistanceM: Double,        // perpendicular distance GPS→route (for logging/UI)
    )

    companion object {
        private const val TAG = "NavEngine"
        private const val ARRIVE_M = 25.0
        private const val DEFAULT_SPEED_MPS = 11.0    // ~40 km/h fallback when GPS speed is 0
        private const val BASE_OFF_ROUTE_M = 40.0     // floor for the dynamic off-route threshold
        private const val OFF_ROUTE_CONSECUTIVE = 4   // fixes in a row before declaring off-route
        private const val ACCURACY_GATE_M = 50.0      // fixes worse than this don't vote off-route
        private const val FWD_WINDOW_M = 500.0        // forward search window ahead of the cursor
        private const val BACK_SEGMENTS = 1           // allow small backward correction for jitter
        private const val RELOCATE_M = 80.0           // if best-in-window is worse, re-acquire globally
        private const val JITTER_BACK_M = 15.0        // tolerated backward slide (GPS jitter)
        private const val BACKWARD_PENALTY = 1e6      // strongly disprefer snapping behind progress
    }

    private var cursor = 0                 // last snapped segment start index (monotonic-ish)
    private var lastCum = 0.0              // last accepted traveled distance (forward-bias anchor)
    private var offRouteVotes = 0
    private var acquired = false           // false until the first successful snap

    private val geom = route.geometry
    private val cum = route.cumulative

    /**
     * @param pos       latest GPS position
     * @param speedMps  GPS speed (m/s); <=0 falls back to a default for ETA only
     * @param accuracyM horizontal accuracy in metres (larger = worse); gates off-route voting
     */
    fun update(pos: GeoPoint, speedMps: Float, accuracyM: Float): Progress {
        if (geom.size < 2) {
            // Degenerate route — should never happen (Router guards), but never throw in nav.
            val only = geom.firstOrNull() ?: pos
            return Progress(only, 0, 0.0, 0.0, 0.0, 0.0, null, 0.0, offRoute = false, arrived = false, snapDistanceM = 0.0)
        }

        // 1. Snap within a forward window around the cursor; re-acquire globally if far off.
        var best = snapInWindow(pos, cursor)
        if (!acquired || best.dist > RELOCATE_M) {
            val global = snapGlobal(pos)
            // Only accept a global re-acquire if it's genuinely better (avoids yanking backward
            // onto a nearer parallel road when we're legitimately mid-route).
            if (!acquired || global.dist < best.dist) best = global
        }
        acquired = true

        // 2. Monotonic cursor: advance forward; permit a tiny backward nudge for GPS jitter only.
        if (best.index >= cursor - BACK_SEGMENTS) {
            cursor = max(cursor, best.index)
        }
        // Progress never runs backward beyond a small jitter tolerance — on a retrace/loop this
        // keeps us on the forward pass instead of collapsing to the earlier, spatially-equal one.
        lastCum = max(lastCum - JITTER_BACK_M, best.cum)

        val traveled = lastCum
        val remaining = (route.totalMeters - traveled).coerceAtLeast(0.0)

        // 3. Off-route with hysteresis + accuracy gate + dynamic threshold.
        val threshold = max(BASE_OFF_ROUTE_M, accuracyM * 1.5)
        val fixReliable = accuracyM in 0f..ACCURACY_GATE_M.toFloat()
        if (best.dist > threshold && fixReliable) {
            offRouteVotes++
        } else {
            offRouteVotes = 0
        }
        val offRoute = offRouteVotes >= OFF_ROUTE_CONSECUTIVE

        // 4. Next maneuver ahead of the snap on the SAME axis (now correct after the axis fix).
        val next = route.maneuvers.firstOrNull {
            it.cumulativeMeters > traveled + 1.0 && it.type != ManeuverType.DEPART
        }
        val distToManeuver = next?.let { (it.cumulativeMeters - traveled).coerceAtLeast(0.0) } ?: remaining

        // 5. Arrival requires TRUE destination proximity (not just remaining≈0, which a mis-snap
        //    near the end could fake).
        val destDist = route.destination?.let { GeoPoint.distMeters(pos, it) } ?: Double.MAX_VALUE
        val arrived = remaining <= ARRIVE_M && destDist <= ARRIVE_M * 2

        val speed = if (speedMps > 0.5f) speedMps.toDouble() else DEFAULT_SPEED_MPS
        val eta = remaining / speed

        DebugLog.d(TAG) {
            "NAVFIX snap=%.0f cur=%d cum=%.0f rem=%.0f dman=%.0f offv=%d off=%b arr=%b acc=%.0f".format(
                best.dist, cursor, traveled, remaining, distToManeuver, offRouteVotes, offRoute, arrived, accuracyM)
        }

        return Progress(
            snapped = best.snap,
            snappedIndex = best.index,
            routeBearing = best.bearing,
            traveledMeters = traveled,
            remainingMeters = remaining,
            distanceToManeuverM = distToManeuver,
            nextManeuver = next,
            etaSeconds = eta,
            offRoute = offRoute,
            arrived = arrived,
            snapDistanceM = best.dist,
        )
    }

    /** Split the route geometry at the current snap into (traveled, ahead) for line trimming. */
    fun split(p: Progress): Pair<List<GeoPoint>, List<GeoPoint>> {
        val i = p.snappedIndex.coerceIn(0, geom.size - 1)
        val traveled = ArrayList<GeoPoint>(i + 2).apply {
            for (k in 0..i) add(geom[k])
            add(p.snapped)
        }
        val ahead = ArrayList<GeoPoint>(geom.size - i + 1).apply {
            add(p.snapped)
            for (k in (i + 1) until geom.size) add(geom[k])
        }
        return traveled to ahead
    }

    private data class Snap(val snap: GeoPoint, val index: Int, val cum: Double, val dist: Double, val bearing: Double)

    private fun snapSegment(pos: GeoPoint, i: Int): Snap {
        val a = geom[i]; val b = geom[i + 1]
        val (proj, t) = GeoPoint.projectOnSegment(pos, a, b)
        val d = GeoPoint.distMeters(pos, proj)
        val segLen = GeoPoint.distMeters(a, b)
        return Snap(proj, i, cum[i] + segLen * t, d, GeoPoint.bearing(a, b))
    }

    private fun snapInWindow(pos: GeoPoint, from: Int): Snap {
        val lo = (from - BACK_SEGMENTS).coerceAtLeast(0)
        var best: Snap? = null
        var bestScore = Double.MAX_VALUE
        var i = lo
        val startCum = cum[lo]
        while (i < geom.size - 1) {
            val s = snapSegment(pos, i)
            // Forward-continuity bias: a candidate that lies BEHIND our current progress (beyond
            // jitter tolerance) is heavily penalized, so on a retrace/loop we stay on the forward
            // pass instead of jumping to the spatially-equal earlier one.
            val backward = if (s.cum < lastCum - JITTER_BACK_M) BACKWARD_PENALTY else 0.0
            val score = s.dist + backward
            if (best == null || score < bestScore) { best = s; bestScore = score }
            if (cum[i] - startCum > FWD_WINDOW_M) break
            i++
        }
        return best ?: snapSegment(pos, lo.coerceAtMost(geom.size - 2))
    }

    private fun snapGlobal(pos: GeoPoint): Snap {
        var best = snapSegment(pos, 0)
        for (i in 1 until geom.size - 1) {
            val s = snapSegment(pos, i)
            if (s.dist < best.dist) best = s
        }
        return best
    }
}
