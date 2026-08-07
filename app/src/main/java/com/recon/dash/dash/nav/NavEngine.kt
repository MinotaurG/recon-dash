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
        val secondManeuver: Maneuver?,   // the maneuver AFTER next — drives the dash's small secondary glyph
        val etaSeconds: Double,
        val offRoute: Boolean,
        val arrived: Boolean,
        val snapDistanceM: Double,        // perpendicular distance GPS→route (for logging/UI)
        val currentStreet: String,        // name of the road the rider is currently on ("" if unknown)
    )

    companion object {
        private const val TAG = "NavEngine"
        private const val ARRIVE_M = 30.0
        private const val DEFAULT_SPEED_MPS = 11.0    // ~40 km/h fallback when GPS speed is 0
        // Off-route distance threshold, TIERED by speed (Google's approach): sparse route
        // polylines + curves/roundabouts produce large transient snap errors (we saw 70-140m on
        // roundabouts that were NOT actually off-route), so the city threshold must be generous.
        private const val OFF_ROUTE_M_SLOW = 50.0     // <30 km/h (city, roundabouts)
        private const val OFF_ROUTE_M_URBAN = 70.0    // 30-60 km/h
        private const val OFF_ROUTE_M_HIGHWAY = 90.0  // >60 km/h
        private const val OFF_ROUTE_CONSECUTIVE = 5   // consecutive off fixes before declaring off-route
        private const val ACCURACY_GATE_M = 40.0      // fixes worse than this don't vote off-route
        // Heading gate: only count as off-route if the rider's heading also DISAGREES with the
        // route direction. On a roundabout you're far from the chord but still heading along it —
        // heading agreement means "still on route" even at a big snap distance (cheap map-matching).
        private const val HEADING_AGREE_DEG = 60.0
        private const val FWD_WINDOW_M = 500.0        // forward search window ahead of the cursor
        private const val BACK_SEGMENTS = 1           // allow small backward correction for jitter
        private const val RELOCATE_M = 150.0          // re-acquire globally only when truly lost
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
     * @param pos        latest GPS position
     * @param speedMps   GPS speed (m/s); <=0 falls back to a default for ETA only
     * @param accuracyM  horizontal accuracy in metres (larger = worse); gates off-route voting
     * @param bearingDeg rider's travel heading in degrees, or null if unknown/stationary; used to
     *                   distinguish "far from the polyline but heading along it" (roundabout, still
     *                   on route) from a genuine wrong turn.
     */
    fun update(pos: GeoPoint, speedMps: Float, accuracyM: Float, bearingDeg: Float? = null): Progress {
        if (geom.size < 2) {
            // Degenerate route — should never happen (Router guards), but never throw in nav.
            val only = geom.firstOrNull() ?: pos
            return Progress(only, 0, 0.0, 0.0, 0.0, 0.0, null, null, 0.0, offRoute = false, arrived = false, snapDistanceM = 0.0, currentStreet = "")
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

        // 3. Off-route: speed-tiered distance threshold + heading agreement + accuracy gate +
        //    consecutive-confirmation. Mirrors Google's principles (a big snap distance alone is
        //    NOT off-route — roundabouts/curves cause that; a wrong HEADING is the real signal).
        val speedKmh = speedMps * 3.6f
        val distThreshold = max(
            when {
                speedKmh > 60f -> OFF_ROUTE_M_HIGHWAY
                speedKmh > 30f -> OFF_ROUTE_M_URBAN
                else -> OFF_ROUTE_M_SLOW
            },
            accuracyM * 1.5,
        )
        val fixReliable = accuracyM in 0f..ACCURACY_GATE_M.toFloat()
        // Heading disagrees with the route direction? (Only trust heading when actually moving.)
        val headingDisagrees = bearingDeg != null && speedMps > 2.0f &&
            angleDiff(bearingDeg.toDouble(), best.bearing) > HEADING_AGREE_DEG
        // Count a vote only when far AND reliable AND (heading disagrees OR very far = 2x threshold).
        val farEnough = best.dist > distThreshold
        val veryFar = best.dist > distThreshold * 2
        if (fixReliable && farEnough && (headingDisagrees || veryFar || bearingDeg == null)) {
            offRouteVotes++
        } else {
            offRouteVotes = 0
        }
        val offRoute = offRouteVotes >= OFF_ROUTE_CONSECUTIVE

        // 4. Next maneuver ahead of the snap on the SAME axis (now correct after the axis fix).
        //    Also the maneuver AFTER that (secondManeuver) — the dash shows its glyph as a small
        //    secondary icon ("then turn X"). We skip ARRIVE for the secondary so we don't preview
        //    "arrive" as a turn.
        val upcoming = route.maneuvers.filter {
            it.cumulativeMeters > traveled + 1.0 && it.type != ManeuverType.DEPART
        }
        val next = upcoming.firstOrNull()
        val second = upcoming.getOrNull(1)?.takeIf { it.type != ManeuverType.ARRIVE }
        val distToManeuver = next?.let { (it.cumulativeMeters - traveled).coerceAtLeast(0.0) } ?: remaining

        // Current street = the road the rider is on NOW: the last maneuver at or before the snap
        // that has a name. (The maneuver's street_names describe the segment it travels along.)
        val currentStreet = route.maneuvers
            .lastOrNull { it.cumulativeMeters <= traveled + 1.0 && it.streetName.isNotBlank() }
            ?.streetName ?: ""

        // 5. Arrival requires TRUE destination proximity (not just remaining≈0, which a mis-snap
        //    near the end could fake).
        val destDist = route.destination?.let { GeoPoint.distMeters(pos, it) } ?: Double.MAX_VALUE
        val arrived = remaining <= ARRIVE_M && destDist <= ARRIVE_M * 2

        val speed = if (speedMps > 0.5f) speedMps.toDouble() else DEFAULT_SPEED_MPS
        val eta = remaining / speed

        // NavSessionManager emits the user-facing NAVFIX line; here we keep only the internal
        // matcher detail (cursor + off-route vote count) that isn't in the snapshot.
        DebugLog.d(TAG) { "match cur=$cursor offv=$offRouteVotes reacq=${best.index}" }

        return Progress(
            snapped = best.snap,
            snappedIndex = best.index,
            routeBearing = best.bearing,
            traveledMeters = traveled,
            remainingMeters = remaining,
            distanceToManeuverM = distToManeuver,
            nextManeuver = next,
            secondManeuver = second,
            etaSeconds = eta,
            offRoute = offRoute,
            arrived = arrived,
            snapDistanceM = best.dist,
            currentStreet = currentStreet,
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

    /** Smallest absolute difference between two bearings, in [0,180]. */
    private fun angleDiff(a: Double, b: Double): Double {
        val d = kotlin.math.abs((a - b) % 360.0)
        return if (d > 180.0) 360.0 - d else d
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
