package com.recon.dash

import com.recon.dash.dash.nav.GeoPoint
import com.recon.dash.dash.nav.PolylineCodec
import org.junit.Assert.*
import org.junit.Test

class PolylineCodecTest {

    @Test
    fun `decode produces correct coordinates`() {
        // Known encoded polyline: straight line from (38.5, -120.2) to (40.7, -120.95) to (43.252, -126.453)
        val encoded = "_p~iF~ps|U_ulLnnqC_mqNvxq`@"
        val points = PolylineCodec.decode(encoded)

        assertEquals(3, points.size)
        assertEquals(38.5, points[0].lat, 0.001)
        assertEquals(-120.2, points[0].lng, 0.001)
        assertEquals(40.7, points[1].lat, 0.001)
        assertEquals(-120.95, points[1].lng, 0.001)
        assertEquals(43.252, points[2].lat, 0.001)
        assertEquals(-126.453, points[2].lng, 0.001)
    }

    @Test
    fun `encode then decode round-trips`() {
        val original = listOf(
            GeoPoint(12.9716, 77.5946),
            GeoPoint(13.0827, 80.2707),
            GeoPoint(12.2958, 76.6394),
        )
        val encoded = PolylineCodec.encode(original)
        val decoded = PolylineCodec.decode(encoded)

        assertEquals(original.size, decoded.size)
        for (i in original.indices) {
            assertEquals(original[i].lat, decoded[i].lat, 0.00001)
            assertEquals(original[i].lng, decoded[i].lng, 0.00001)
        }
    }

    @Test
    fun `decode handles empty string`() {
        val points = PolylineCodec.decode("")
        assertTrue(points.isEmpty())
    }

    @Test
    fun `encode empty list returns empty string`() {
        val encoded = PolylineCodec.encode(emptyList())
        assertEquals("", encoded)
    }
}
