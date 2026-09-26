package com.recon.dash

import com.recon.dash.dash.nav.GeoPoint
import com.recon.dash.dash.nav.RouteOptions
import com.recon.dash.dash.nav.Router
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** The Valhalla request must carry the rider's heading on reroutes (the reroute-loop fix). */
class RouterRequestTest {

    private val from = GeoPoint(17.4124, 78.2986)
    private val to = GeoPoint(17.45, 78.35)

    private fun origin(heading: Float?): JSONObject =
        JSONObject(Router.buildRequest(from, to, RouteOptions(), heading))
            .getJSONArray("locations").getJSONObject(0)

    @Test
    fun `reroute origin carries heading and tolerance`() {
        val o = origin(92.7f)
        assertEquals(92, o.getInt("heading"))
        assertTrue(o.has("heading_tolerance"))
    }

    @Test
    fun `heading is normalised into 0-359`() {
        assertEquals(350, origin(-10f).getInt("heading"))
        assertEquals(0, origin(360f).getInt("heading"))
    }

    @Test
    fun `no heading leaves the origin unconstrained`() {
        val o = origin(null)
        assertFalse(o.has("heading"))
        assertFalse(o.has("heading_tolerance"))
    }
}
