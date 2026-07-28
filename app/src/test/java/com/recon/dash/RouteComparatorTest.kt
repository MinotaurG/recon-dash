package com.recon.dash

import com.recon.dash.dash.nav.GeoPoint
import com.recon.dash.dash.nav.Route
import com.recon.dash.dash.nav.RouteComparator
import org.junit.Assert.*
import org.junit.Test

/** Verifies the pure Valhalla-vs-Google divergence metric. */
class RouteComparatorTest {

    private fun routeOf(vararg pts: GeoPoint): Route {
        val geom = pts.toList()
        val cum = DoubleArray(geom.size)
        for (i in 1 until geom.size) cum[i] = cum[i - 1] + GeoPoint.distMeters(geom[i - 1], geom[i])
        return Route(geom, emptyList(), cum.last(), cum.last() / 11.0, cum)
    }

    @Test
    fun `identical routes overlap fully`() {
        val a = routeOf(GeoPoint(17.40, 78.32), GeoPoint(17.40, 78.34), GeoPoint(17.40, 78.36))
        val b = routeOf(GeoPoint(17.40, 78.32), GeoPoint(17.40, 78.34), GeoPoint(17.40, 78.36))
        val d = RouteComparator.compare(a, b)
        assertEquals(1.0, d.overlapPct, 0.001)
        assertEquals(0.0, d.deltaMeters, 1.0)
    }

    @Test
    fun `parallel roads far apart barely overlap`() {
        // Two east-west lines ~500 m apart (~0.0045 deg latitude) — different roads.
        val v = routeOf(GeoPoint(17.400, 78.32), GeoPoint(17.400, 78.36))
        val g = routeOf(GeoPoint(17.4045, 78.32), GeoPoint(17.4045, 78.36))
        val d = RouteComparator.compare(v, g)
        assertTrue("far-apart parallel roads should have low overlap, got ${d.overlapPct}", d.overlapPct < 0.1)
    }

    @Test
    fun `partial divergence lands mid-range`() {
        // First half identical, second half diverges north — expect roughly half overlap.
        val v = routeOf(GeoPoint(17.400, 78.32), GeoPoint(17.400, 78.34), GeoPoint(17.400, 78.36))
        val g = routeOf(GeoPoint(17.400, 78.32), GeoPoint(17.400, 78.34), GeoPoint(17.410, 78.36))
        val d = RouteComparator.compare(v, g)
        assertTrue("partial overlap in (0.2,0.9), got ${d.overlapPct}", d.overlapPct in 0.2..0.9)
    }

    @Test
    fun `delta distance and duration are valhalla minus google`() {
        val v = routeOf(GeoPoint(17.40, 78.32), GeoPoint(17.40, 78.40))  // longer
        val g = routeOf(GeoPoint(17.40, 78.32), GeoPoint(17.40, 78.36))  // shorter
        val d = RouteComparator.compare(v, g)
        assertTrue("Valhalla longer => positive deltaMeters", d.deltaMeters > 0.0)
        assertEquals(v.totalMeters, d.valhallaMeters, 1.0)
        assertEquals(g.totalMeters, d.googleMeters, 1.0)
    }
}
