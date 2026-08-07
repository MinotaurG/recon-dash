package com.recon.dash

import com.recon.dash.dash.nav.*
import org.junit.Assert.*
import org.junit.Test

/** Basic behavior of the stateful [NavEngine] plus [GeoPoint] math. */
class NavEngineTest {

    private fun straightRoute(): Route {
        // South-to-north line: (0,0) → (0.005,0) → (0.01,0), ~1.1 km.
        val geom = listOf(GeoPoint(0.0, 0.0), GeoPoint(0.005, 0.0), GeoPoint(0.01, 0.0))
        val cum = DoubleArray(3)
        cum[1] = GeoPoint.distMeters(geom[0], geom[1])
        cum[2] = cum[1] + GeoPoint.distMeters(geom[1], geom[2])
        return Route(
            geometry = geom,
            maneuvers = listOf(
                Maneuver(ManeuverType.DEPART, "Depart", geom[0], 0.0),
                Maneuver(ManeuverType.ARRIVE, "Arrive", geom[2], cum[2]),
            ),
            totalMeters = cum[2],
            totalSeconds = cum[2] / 11.0,
            cumulative = cum,
        )
    }

    /** Good-accuracy fix (metres). */
    private fun NavEngine.fix(p: GeoPoint, speed: Float = 10f, acc: Float = 5f) = update(p, speed, acc)

    @Test
    fun `snaps rider to route and reports remaining distance`() {
        val eng = NavEngine(straightRoute())
        val p = eng.fix(GeoPoint(0.005, 0.0001)) // slightly off, near midpoint
        assertEquals(0.005, p.snapped.lat, 0.001)
        assertEquals(0.0, p.snapped.lng, 0.001)
        assertTrue(p.remainingMeters > 0)
        assertFalse(p.offRoute)
        assertFalse(p.arrived)
    }

    @Test
    fun `single far fix does not immediately declare off-route (hysteresis)`() {
        val eng = NavEngine(straightRoute())
        val far = GeoPoint(0.005, 0.01) // ~1 km east
        assertFalse("one off fix must not trip off-route", eng.fix(far).offRoute)
    }

    @Test
    fun `sustained far fixes declare off-route after hysteresis`() {
        val eng = NavEngine(straightRoute())
        val far = GeoPoint(0.005, 0.01)
        var off = false
        // Needs ACCURACY_SETTLE_FIXES (3) good-accuracy fixes to trust the value + OFF_ROUTE_
        // CONSECUTIVE (5) off votes = 8 total. 10 sustained far fixes comfortably trips it.
        repeat(10) { off = eng.fix(far).offRoute }
        assertTrue("sustained off-route should trip", off)
    }

    @Test
    fun `low-accuracy fixes never vote off-route`() {
        val eng = NavEngine(straightRoute())
        val far = GeoPoint(0.005, 0.01)
        var off = false
        repeat(10) { off = eng.fix(far, acc = 500f).offRoute } // coarse NETWORK-like fix
        assertFalse("coarse fixes must not trigger off-route", off)
    }

    @Test
    fun `arrival requires true destination proximity`() {
        val eng = NavEngine(straightRoute())
        // Walk up to the end.
        eng.fix(GeoPoint(0.0, 0.0)); eng.fix(GeoPoint(0.005, 0.0))
        assertTrue(eng.fix(GeoPoint(0.00999, 0.0)).arrived)
    }

    @Test
    fun `ETA uses GPS speed when available`() {
        val fast = NavEngine(straightRoute()).fix(GeoPoint(0.0, 0.0), speed = 20f)
        val slow = NavEngine(straightRoute()).fix(GeoPoint(0.0, 0.0), speed = 5f)
        assertTrue(fast.etaSeconds < slow.etaSeconds)
    }

    @Test
    fun `next maneuver skips DEPART`() {
        val p = NavEngine(straightRoute()).fix(GeoPoint(0.0, 0.0))
        assertEquals(ManeuverType.ARRIVE, p.nextManeuver?.type)
    }

    @Test
    fun `GeoPoint distMeters gives reasonable values`() {
        val dist = GeoPoint.distMeters(GeoPoint(12.9716, 77.5946), GeoPoint(13.0827, 80.2707))
        assertTrue(dist in 280_000.0..300_000.0)
    }

    @Test
    fun `GeoPoint bearing gives correct direction`() {
        assertEquals(0.0, GeoPoint.bearing(GeoPoint(0.0, 0.0), GeoPoint(1.0, 0.0)), 1.0)
    }

    @Test
    fun `GeoPoint projectOnSegment clamps to endpoints`() {
        val a = GeoPoint(0.0, 0.0); val b = GeoPoint(0.0, 1.0)
        val (proj, t) = GeoPoint.projectOnSegment(GeoPoint(0.0, -0.5), a, b)
        assertEquals(0.0, t, 0.001)
        assertEquals(a.lat, proj.lat, 0.0001)
        assertEquals(a.lng, proj.lng, 0.0001)
    }
}
