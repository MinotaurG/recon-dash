package com.recon.dash.dash.nav

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the reroute guards added after the 2026-08-06 HITEC-City false reroute:
 * a stationary rider on the correct road was rerouted because GPS drifted ~128m in an urban canyon
 * and the reported accuracy had just recovered from a 344m spike to ~25m (overconfident).
 *
 * Guards under test (NavEngine):
 *  - low-speed: never vote off-route below OFF_ROUTE_MIN_SPEED_MPS.
 *  - accuracy-settle: a fix votes only after ACCURACY_SETTLE_FIXES consecutive good-accuracy fixes.
 */
class NavEngineRerouteGuardTest {

    // A straight ~1 km west-east route near the real HITEC-City coordinates.
    private fun straightRoute(): Route {
        val pts = (0..10).map { GeoPoint(17.4400, 78.3770 + it * 0.001) }  // ~0.001 lon ≈ 106 m steps
        val cum = DoubleArray(pts.size)
        for (i in 1 until pts.size) cum[i] = cum[i - 1] + GeoPoint.distMeters(pts[i - 1], pts[i])
        return Route(
            geometry = pts,
            maneuvers = listOf(
                Maneuver(ManeuverType.DEPART, "Start", pts.first(), 0.0),
                Maneuver(ManeuverType.ARRIVE, "Arrive", pts.last(), cum.last()),
            ),
            totalMeters = cum.last(), totalSeconds = 120.0, cumulative = cum,
        )
    }

    /** A point ~130 m NORTH of the route (perpendicular drift), like the urban-canyon jump. */
    private fun driftedPoint() = GeoPoint(17.4412, 78.3775)  // ~130 m off the 17.4400 line

    @Test fun stationaryRiderNeverReroutes_evenWithBigDrift() {
        val eng = NavEngine(straightRoute())
        // Settle on-route first (moving, good accuracy).
        repeat(4) { eng.update(GeoPoint(17.4400, 78.3773), speedMps = 8f, accuracyM = 5f) }
        // Now: stopped (v=0), drifted 130 m, accuracy "good". Many fixes — must NOT go off-route.
        var offRoute = false
        repeat(10) {
            val p = eng.update(driftedPoint(), speedMps = 0f, accuracyM = 20f)
            offRoute = offRoute || p.offRoute
        }
        assertFalse("Stationary rider must never be declared off-route", offRoute)
    }

    @Test fun justRecoveredAccuracyDoesNotImmediatelyReroute() {
        val eng = NavEngine(straightRoute())
        repeat(4) { eng.update(GeoPoint(17.4400, 78.3773), speedMps = 8f, accuracyM = 5f) }
        // Accuracy spike (ignored), then MOVING + drifted + accuracy just "recovered" to 25 m.
        eng.update(driftedPoint(), speedMps = 8f, accuracyM = 344f)
        // Only 2 good-accuracy fixes < ACCURACY_SETTLE_FIXES (3): must not have voted enough yet.
        val p1 = eng.update(driftedPoint(), speedMps = 8f, accuracyM = 25f)
        val p2 = eng.update(driftedPoint(), speedMps = 8f, accuracyM = 25f)
        assertFalse(p1.offRoute)
        assertFalse(p2.offRoute)
    }

    @Test fun genuinelyOffRouteStillReroutes_whenMovingAndSettled() {
        val eng = NavEngine(straightRoute())
        repeat(4) { eng.update(GeoPoint(17.4400, 78.3773), speedMps = 8f, accuracyM = 5f) }
        // Moving, consistently good accuracy, consistently far off with wrong heading (north) —
        // this SHOULD eventually reroute (the guards must not suppress a real off-route).
        var offRoute = false
        repeat(10) {
            val p = eng.update(driftedPoint(), speedMps = 8f, accuracyM = 5f, bearingDeg = 0f)
            offRoute = offRoute || p.offRoute
        }
        assertTrue("A moving, well-located, genuinely-off rider must still reroute", offRoute)
    }
}
