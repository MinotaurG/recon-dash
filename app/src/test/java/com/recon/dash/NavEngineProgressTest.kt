package com.recon.dash

import com.recon.dash.dash.nav.*
import org.junit.Assert.*
import org.junit.Test

/** Reliability behaviors of the stateful NavEngine: monotonic progress, windowed snap, trim. */
class NavEngineProgressTest {

    // Fake clock: each fix is 1 s after the previous one (off-route confirmation is time-based).
    private var nowMs = 0L
    private val tick: () -> Long = { nowMs += 1_000; nowMs }

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
        val eng = NavEngine(route, tick)
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
        val eng = NavEngine(route, tick)
        eng.update(GeoPoint(0.0, 0.001), 10f, 5f)
        val p = eng.update(GeoPoint(0.0, 0.0032), 10f, 5f)
        assertTrue("snap stays on the line near the rider", p.snapDistanceM < 60.0)
    }

    @Test
    fun `split reconstructs the full route and traveled length matches progress`() {
        val route = routeOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.004), GeoPoint(0.0, 0.008))
        val eng = NavEngine(route, tick)
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
    fun `far-from-polyline but heading-along is NOT off-route (roundabout case)`() {
        // The real ride bug: on a roundabout the rider is 70-140m from the sparse route chord but
        // still travelling ALONG the route direction. Heading agreement must keep it on-route.
        // Route heads east (bearing ~90°).
        val route = routeOf(GeoPoint(17.40, 78.32), GeoPoint(17.40, 78.33), GeoPoint(17.40, 78.34))
        val eng = NavEngine(route, tick)
        // Rider ~80m north of the line (roundabout arc) but heading EAST (~90°), fast.
        var off = false
        repeat(10) { off = eng.update(GeoPoint(17.4007, 78.325), speedMps = 15f, accuracyM = 5f, bearingDeg = 90f).offRoute }
        assertFalse("heading along the route must not trip off-route despite big snap distance", off)
    }

    @Test
    fun `far AND heading-against the route IS off-route (real wrong turn)`() {
        val route = routeOf(GeoPoint(17.40, 78.32), GeoPoint(17.40, 78.33), GeoPoint(17.40, 78.34))
        val eng = NavEngine(route, tick)
        // Rider well off the line AND heading NORTH (~0°) while route runs east — a real deviation.
        var off = false
        repeat(8) { off = eng.update(GeoPoint(17.405, 78.325), speedMps = 15f, accuracyM = 5f, bearingDeg = 0f).offRoute }
        assertTrue("far + wrong heading is a genuine off-route", off)
    }

    @Test
    fun `off-route recovery resets the vote counter`() {
        val route = routeOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.006))
        val eng = NavEngine(route, tick)
        val far = GeoPoint(0.0, 0.02)
        repeat(3) { eng.update(far, 10f, 5f) }          // 3 off (below the 4-consecutive threshold)
        val back = eng.update(GeoPoint(0.0, 0.003), 10f, 5f)
        assertFalse("returning to route clears off-route", back.offRoute)
        // One more far fix should NOT immediately re-trip (counter was reset).
        assertFalse(eng.update(far, 10f, 5f).offRoute)
    }

    @Test
    fun `off-route needs sustained time, not just fix count`() {
        val route = routeOf(GeoPoint(17.40, 78.32), GeoPoint(17.40, 78.33), GeoPoint(17.40, 78.34))
        var t = 0L
        val eng = NavEngine(route) { t }
        val far = GeoPoint(17.405, 78.325)
        // Many fixes arriving in a burst (same instant) must NOT confirm off-route...
        repeat(10) { assertFalse(eng.update(far, 15f, 5f, bearingDeg = 0f).offRoute) }
        // ...but once 4 s have elapsed with the rider still off, it confirms.
        t = 4_000L
        assertTrue(eng.update(far, 15f, 5f, bearingDeg = 0f).offRoute)
    }

    @Test
    fun `suspect state precedes confirmed off-route and clears on rejoin`() {
        val route = routeOf(GeoPoint(17.40, 78.32), GeoPoint(17.40, 78.33), GeoPoint(17.40, 78.34))
        val eng = NavEngine(route, tick)
        // Settle on-route first (off-route votes need a few consecutive good-accuracy fixes).
        repeat(3) { eng.update(GeoPoint(17.40, 78.321 + it * 0.0002), 15f, 5f, bearingDeg = 90f) }
        val far = GeoPoint(17.405, 78.325)
        val first = eng.update(far, 15f, 5f, bearingDeg = 0f)
        assertTrue(first.offRouteSuspect)
        assertFalse(first.offRoute)
        // Rider rejoins before confirmation → silently back on route.
        val back = eng.update(GeoPoint(17.40, 78.326), 15f, 5f, bearingDeg = 90f)
        assertFalse(back.offRouteSuspect)
        assertFalse(back.offRoute)
    }

    @Test
    fun `marker leaves the line on a detour long before off-route confirms`() {
        val route = routeOf(GeoPoint(17.40, 78.32), GeoPoint(17.40, 78.33), GeoPoint(17.40, 78.34))
        val eng = NavEngine(route, tick)
        assertTrue(eng.update(GeoPoint(17.40, 78.322), 10f, 4f, bearingDeg = 90f).puckOnRoute)
        // ~35 m north, heading north: turned off. Marker must go raw immediately.
        val p = eng.update(GeoPoint(17.40032, 78.3222), 10f, 4f, bearingDeg = 0f)
        assertFalse("marker should show real position on a detour", p.puckOnRoute)
        assertFalse("off-route is not confirmed yet", p.offRoute)
    }

    @Test
    fun `marker stays on the line for normal GPS jitter`() {
        val route = routeOf(GeoPoint(17.40, 78.32), GeoPoint(17.40, 78.33), GeoPoint(17.40, 78.34))
        val eng = NavEngine(route, tick)
        // ±~8 m lateral jitter while heading along the route.
        for (i in 0 until 10) {
            val lat = 17.40 + if (i % 2 == 0) 0.00007 else -0.00007
            assertTrue(eng.update(GeoPoint(lat, 78.321 + i * 0.0001), 10f, 5f, bearingDeg = 90f).puckOnRoute)
        }
    }

    @Test
    fun `marker rejoins the line with hysteresis`() {
        val route = routeOf(GeoPoint(17.40, 78.32), GeoPoint(17.40, 78.33), GeoPoint(17.40, 78.34))
        val eng = NavEngine(route, tick)
        eng.update(GeoPoint(17.40, 78.322), 10f, 4f, bearingDeg = 90f)
        assertFalse(eng.update(GeoPoint(17.4003, 78.323), 10f, 4f, bearingDeg = 90f).puckOnRoute) // ~33 m off
        // ~16 m off: inside the leave radius but outside the return radius → stays raw.
        assertFalse(eng.update(GeoPoint(17.400145, 78.324), 10f, 4f, bearingDeg = 90f).puckOnRoute)
        // ~5 m off → back on the line.
        assertTrue(eng.update(GeoPoint(17.400045, 78.325), 10f, 4f, bearingDeg = 90f).puckOnRoute)
    }
}
