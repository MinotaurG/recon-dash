package com.recon.dash.util

import com.recon.dash.dash.map.Mercator
import com.recon.dash.dash.nav.PolylineCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parity test for [NativeGeo]. In a plain JVM unit test the native `.so` cannot load,
 * so `NativeGeo.isAvailable` is false and every call takes the KOTLIN FALLBACK path.
 * This proves the fallback is behaviourally identical to [PolylineCodec] + [Mercator]
 * — the same contract the C++ core is verified against by geo_parity_test.cpp on-host.
 */
class NativeGeoParityTest {

    private val samples = listOf(
        // Google canonical example (precision 5)
        "_p~iF~ps|U_ulLnnqC_mqNvxq`@",
        // Short Hyderabad-ish fragment
        "_kb_Cgw_xM_ibE_ibE",
        "",
    )

    @Test
    fun `native lib is not loaded in JVM tests so fallback is exercised`() {
        assertTrue("Expected Kotlin fallback in JVM test", !NativeGeo.isAvailable)
    }

    @Test
    fun `decode matches PolylineCodec at precision 5 and 6`() {
        for (enc in samples) {
            for (precision in intArrayOf(5, 6)) {
                val expected = PolylineCodec.decode(enc, precision)
                val actual = NativeGeo.decode(enc, precision)
                assertEquals("point count ($enc, p=$precision)", expected.size, actual.size)
                for (i in expected.indices) {
                    assertEquals("lat[$i]", expected[i].lat, actual[i].lat, 1e-9)
                    assertEquals("lng[$i]", expected[i].lng, actual[i].lng, 1e-9)
                }
            }
        }
    }

    @Test
    fun `decodeAndProjectPixels matches Kotlin decode plus Mercator`() {
        val enc = samples[0]
        for (zoom in intArrayOf(11, 15, 17, 19)) {
            val flat = NativeGeo.decodeAndProjectPixels(enc, precision = 5, zoom = zoom)
            val pts = PolylineCodec.decode(enc, 5)
            assertEquals(pts.size * 2, flat.size)
            val ts = Mercator.TILE_SIZE
            for (i in pts.indices) {
                val expX = (Mercator.lngToTileX(pts[i].lng, zoom) * ts).toFloat()
                val expY = (Mercator.latToTileY(pts[i].lat, zoom) * ts).toFloat()
                assertEquals("x[$i] z=$zoom", expX, flat[i * 2], 0.01f)
                assertEquals("y[$i] z=$zoom", expY, flat[i * 2 + 1], 0.01f)
            }
        }
    }

    @Test
    fun `empty input yields empty output`() {
        assertTrue(NativeGeo.decode("", 6).isEmpty())
        assertEquals(0, NativeGeo.decodeAndProjectPixels("", 6, 15).size)
    }
}
