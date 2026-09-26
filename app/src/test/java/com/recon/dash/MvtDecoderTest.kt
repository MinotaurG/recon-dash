package com.recon.dash

import com.recon.dash.map.MvtDecoder
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class MvtDecoderTest {

    // ── Geometry: the worked examples from the MVT 2.1 spec (§4.3.5) ──

    @Test
    fun `point geometry`() {
        val parts = MvtDecoder.decodeGeometry(intArrayOf(9, 50, 34), MvtDecoder.GEOM_POINT)
        assertArrayEquals(intArrayOf(25, 17), parts.single())
    }

    @Test
    fun `linestring geometry`() {
        val parts = MvtDecoder.decodeGeometry(intArrayOf(9, 4, 4, 18, 0, 16, 16, 0), MvtDecoder.GEOM_LINE)
        assertArrayEquals(intArrayOf(2, 2, 2, 10, 10, 10), parts.single())
    }

    @Test
    fun `multi-linestring splits into parts`() {
        val parts = MvtDecoder.decodeGeometry(
            intArrayOf(9, 4, 4, 18, 0, 16, 16, 0, 9, 17, 17, 10, 4, 8), MvtDecoder.GEOM_LINE,
        )
        assertEquals(2, parts.size)
        assertArrayEquals(intArrayOf(2, 2, 2, 10, 10, 10), parts[0])
        assertArrayEquals(intArrayOf(1, 1, 3, 5), parts[1])
    }

    @Test
    fun `polygon ring`() {
        val parts = MvtDecoder.decodeGeometry(intArrayOf(9, 6, 12, 18, 10, 12, 24, 44, 15), MvtDecoder.GEOM_POLYGON)
        assertArrayEquals(intArrayOf(3, 6, 8, 12, 20, 34), parts.single())
    }

    // ── Full tile: layer name, extent, keys/values, tags ──

    @Test
    fun `decodes layer, features and tags`() {
        val value = msg { string(1, "primary") }
        val feature = msg {
            packed(2, intArrayOf(0, 0))                        // tags: key 0 = value 0
            varint(3, MvtDecoder.GEOM_LINE.toLong())
            packed(4, intArrayOf(9, 4, 4, 18, 0, 16, 16, 0))
        }
        val layer = msg {
            varint(15, 2)                                      // version
            string(1, "transportation")
            bytes(2, feature)
            string(3, "class")
            bytes(4, value)
            varint(5, 4096)
        }
        val tile = msg { bytes(3, layer) }

        val layers = MvtDecoder.decode(tile)
        val l = layers.getValue("transportation")
        assertEquals(4096, l.extent)
        val f = l.features.single()
        assertEquals(MvtDecoder.GEOM_LINE, f.type)
        assertEquals("primary", f.str("class"))
        assertNull(f.tag("missing"))
        assertArrayEquals(intArrayOf(2, 2, 2, 10, 10, 10), f.parts.single())
    }

    // ── Tiny protobuf writer for building test tiles ──

    private class Msg {
        val out = ByteArrayOutputStream()
        fun raw(v: Long) { var x = v; while (x and 0x7F.inv().toLong() != 0L) { out.write(((x and 0x7F) or 0x80).toInt()); x = x ushr 7 }; out.write(x.toInt()) }
        fun varint(field: Int, v: Long) { raw((field shl 3).toLong()); raw(v) }
        fun bytes(field: Int, b: ByteArray) { raw((field shl 3 or 2).toLong()); raw(b.size.toLong()); out.write(b) }
        fun string(field: Int, s: String) = bytes(field, s.toByteArray())
        fun packed(field: Int, a: IntArray) = bytes(field, Msg().apply { a.forEach { raw(it.toLong()) } }.out.toByteArray())
    }

    private fun msg(block: Msg.() -> Unit): ByteArray = Msg().apply(block).out.toByteArray()
}
