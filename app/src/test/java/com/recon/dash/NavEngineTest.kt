package com.recon.dash

import com.recon.dash.dash.nav.*
import org.junit.Assert.*
import org.junit.Test

class NavEngineTest {

    private fun straightRoute(): Route {
        // Simple south-to-north line: 0,0 → 0,0.01 (~1.1 km)
        val geom = listOf(
            GeoPoint(0.0, 0.0),
            GeoPoint(0.005, 0.0),
            GeoPoint(0.01, 0.0),
        )
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

    @Test
    fun `progress snaps rider to route and reports correct remaining distance`() {
        val route = straightRoute()
        val riderPos = GeoPoint(0.005, 0.0001) // slightly off the line, near midpoint

        val progress = NavEngine.progress(route, riderPos, 10f)

        // Should snap to approximately (0.005, 0.0)
        assertEquals(0.005, progress.snapped.lat, 0.001)
        assertEquals(0.0, progress.snapped.lng, 0.001)

        // Remaining should be approximately half the total
        assertTrue(progress.remainingMeters > 0)
        assertTrue(progress.remainingMeters < route.totalMeters)
        assertFalse(progress.offRoute)
        assertFalse(progress.arrived)
    }

    @Test
    fun `progress detects off-route when rider is far from line`() {
        val route = straightRoute()
        val farAway = GeoPoint(0.005, 0.01) // ~1km east of the route

        val progress = NavEngine.progress(route, farAway, 10f)

        assertTrue(progress.offRoute)
    }

    @Test
    fun `progress detects arrival when near destination`() {
        val route = straightRoute()
        val nearEnd = GeoPoint(0.00999, 0.0) // very close to end

        val progress = NavEngine.progress(route, nearEnd, 10f)

        assertTrue(progress.arrived)
    }

    @Test
    fun `progress ETA uses GPS speed when available`() {
        val route = straightRoute()
        val start = GeoPoint(0.0, 0.0)

        val fast = NavEngine.progress(route, start, 20f) // 20 m/s = 72 km/h
        val slow = NavEngine.progress(route, start, 5f)  // 5 m/s = 18 km/h

        assertTrue(fast.etaSeconds < slow.etaSeconds)
    }

    @Test
    fun `progress next maneuver skips DEPART`() {
        val route = straightRoute()
        val start = GeoPoint(0.0, 0.0)

        val progress = NavEngine.progress(route, start, 10f)

        // Should skip DEPART and point to ARRIVE
        assertNotNull(progress.nextManeuver)
        assertEquals(ManeuverType.ARRIVE, progress.nextManeuver?.type)
    }

    @Test
    fun `GeoPoint distMeters gives reasonable values`() {
        val a = GeoPoint(12.9716, 77.5946) // Bangalore
        val b = GeoPoint(13.0827, 80.2707) // Chennai

        val dist = GeoPoint.distMeters(a, b)
        // ~290 km
        assertTrue(dist > 280_000)
        assertTrue(dist < 300_000)
    }

    @Test
    fun `GeoPoint bearing gives correct direction`() {
        val south = GeoPoint(0.0, 0.0)
        val north = GeoPoint(1.0, 0.0)

        val bearing = GeoPoint.bearing(south, north)
        assertEquals(0.0, bearing, 1.0) // due north
    }

    @Test
    fun `GeoPoint projectOnSegment clamps to endpoints`() {
        val a = GeoPoint(0.0, 0.0)
        val b = GeoPoint(0.0, 1.0)
        val behind = GeoPoint(0.0, -0.5) // behind segment start

        val (proj, t) = GeoPoint.projectOnSegment(behind, a, b)
        assertEquals(0.0, t, 0.001) // clamped to start
        assertEquals(a.lat, proj.lat, 0.0001)
        assertEquals(a.lng, proj.lng, 0.0001)
    }
}
