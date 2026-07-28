package com.recon.dash

import com.recon.dash.dash.nav.GoogleRoutesClient
import com.recon.dash.dash.nav.RouterResult
import org.junit.Assert.*
import org.junit.Test

/** Parses a canned computeRoutes response into our Route type on the haversine axis. */
class GoogleRoutesParseTest {

    // encodedPolyline "_p~iF~ps|U_ulLnnqC_mqNvxq`@" is Google's canonical example
    // (Mountain View area), precision-5. duration "3600s", distanceMeters 12000.
    private val sample = """
        {"routes":[{"distanceMeters":12000,"duration":"3600s",
        "polyline":{"encodedPolyline":"_p~iF~ps|U_ulLnnqC_mqNvxq`@"}}]}
    """.trimIndent()

    @Test
    fun `parses distance duration and geometry`() {
        val result = GoogleRoutesClient.parse(sample)
        assertTrue(result is RouterResult.Success)
        val route = (result as RouterResult.Success).route
        assertEquals(12000.0, route.totalMeters, 0.5)
        assertEquals(3600.0, route.totalSeconds, 0.5)
        assertEquals(3, route.geometry.size)          // the canonical polyline decodes to 3 points
        assertEquals(route.geometry.size, route.cumulative.size)
        assertTrue(route.maneuvers.isEmpty())         // field mask omits maneuvers by design
    }

    @Test
    fun `empty routes array is a failure not a crash`() {
        val result = GoogleRoutesClient.parse("""{"routes":[]}""")
        assertTrue(result is RouterResult.Failure)
    }

    @Test
    fun `missing routes key is a failure`() {
        val result = GoogleRoutesClient.parse("""{}""")
        assertTrue(result is RouterResult.Failure)
    }
}
