package com.recon.dash

import com.recon.dash.dash.nav.*
import org.junit.Assert.*
import org.junit.Test

/** Reliability behaviors of the stateful NavEngine: monotonic progress, windowed snap, trim. */
class NavEngineProgressTest {

    private fun routeOf(vararg pts: GeoPoint): Route {
        val geom = pts.toList()
        val cum = DoubleArray(geom.size)
        for (i in 1 until geom.size) cum[i] = cum[i - 1] + GeoPoint.distMeters(geom[i - 1], geom[i])
        return Route(geom, emptyList(), cum.last(), cum.last() / 11.0, cum)
    }

    @Test
    fun `progress does not snap backward on an out-and-back route`() {
        // Out to the east then back to start — the return pass is spatially near the outbound.
        val route = routeOf(
            GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.002), GeoPoint(0.0, 0.004), // out
            GeoPoint(0.0, 0.002), GeoPoint(0.0, 0.0),                        // back
        )
        val eng = NavEngine(route)
        // Advance out to the far point.
        eng.update(GeoPoint(0.0, 0.001), 10f, 5f)
        val atFar = eng.update(GeoPoint(0.0, 0.0039), 10f, 5f)
        val farTraveled = atFar.traveledMeters
        // Now a fix near the start location again (on the return leg). Because progress is
        // monotonic, traveled must NOT collapse back to ~0 (which the old global-nearest did).
        val onReturn = eng.update(GeoPoint(0.0, 0.0021), 10f, 5f)
        assertTrue(
            "traveled should keep advancing, not jump back to the outbound pass",
            onReturn.traveledMeters >= farTraveled - 50.0,
        )
    }

    @Test
    fun `windowed snap prefers the near pass over a far parallel road`() {
        // Two parallel east-west lines ~500 m apart, connected — a global nearest could jump
        // to the wrong carriageway. The engine should track the one it's on.
        val route = routeOf(
            GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.003), GeoPoint(0.0, 0.006),
        )
        val eng = NavEngine(route)
        eng.update(GeoPoint(0.0, 0.001), 10f, 5f)
        val p = eng.update(GeoPoint(0.0, 0.0032), 10f, 5f)
        assertTrue("snap stays on the line near the rider", p.snapDistanceM < 60.0)
    }

    @Test
    fun `split reconstructs the full route and traveled length matches progress`() {
        val route = routeOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.004), GeoPoint(0.0, 0.008))
        val eng = NavEngine(route)
        val p = eng.update(GeoPoint(0.0, 0.003), 10f, 5f)
        val (traveled, ahead) = eng.split(p)
        // Traveled ends at the snap; ahead starts at the snap.
        assertEquals(p.snapped.lng, traveled.last().lng, 1e-9)
        assertEquals(p.snapped.lng, ahead.first().lng, 1e-9)
        // Traveled polyline length ≈ traveledMeters.
        var tLen = 0.0
        for (i in 1 until traveled.size) tLen += GeoPoint.distMeters(traveled[i - 1], traveled[i])
        assertEquals(p.traveledMeters, tLen, 5.0)
    }

    @Test
    fun `off-route recovery resets the vote counter`() {
        val route = routeOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.006))
        val eng = NavEngine(route)
        val far = GeoPoint(0.0, 0.02)
        repeat(3) { eng.update(far, 10f, 5f) }          // 3 off (below the 4-consecutive threshold)
        val back = eng.update(GeoPoint(0.0, 0.003), 10f, 5f)
        assertFalse("returning to route clears off-route", back.offRoute)
        // One more far fix should NOT immediately re-trip (counter was reset).
        assertFalse(eng.update(far, 10f, 5f).offRoute)
    }
}
