package com.recon.dash.map

/**
 * Minimal Mapbox Vector Tile (MVT v2) decoder — just enough to draw a base map: layer names,
 * feature geometry, and string/int tag values. Dependency-free (no protobuf library) and pure
 * Kotlin, so it runs in JVM unit tests.
 *
 * Spec: https://github.com/mapbox/vector-tile-spec/tree/master/2.1
 */
object MvtDecoder {

    const val GEOM_POINT = 1
    const val GEOM_LINE = 2
    const val GEOM_POLYGON = 3

    class Feature(
        val type: Int,
        /** Decoded geometry: one IntArray of interleaved x,y (tile extent coords) per part/ring. */
        val parts: List<IntArray>,
        private val tags: IntArray,
        private val layer: Layer,
    ) {
        /** Tag value for [key] (String, Long, Double or Boolean), or null. */
        fun tag(key: String): Any? {
            val k = layer.keys.indexOf(key)
            if (k < 0) return null
            var i = 0
            while (i + 1 < tags.size) {
                if (tags[i] == k) return layer.values.getOrNull(tags[i + 1])
                i += 2
            }
            return null
        }

        fun str(key: String): String? = tag(key) as? String
        fun num(key: String): Long? = when (val v = tag(key)) {
            is Long -> v
            is Double -> v.toLong()
            else -> null
        }
    }

    class Layer(val name: String, val extent: Int, val keys: List<String>, val values: List<Any?>) {
        val features = ArrayList<Feature>()
    }

    fun decode(bytes: ByteArray): Map<String, Layer> {
        val out = HashMap<String, Layer>()
        val r = Reader(bytes, 0, bytes.size)
        while (r.hasMore()) {
            val key = r.varint().toInt()
            if (key == (3 shl 3 or 2)) {           // Tile.layers (field 3, length-delimited)
                val len = r.varint().toInt()
                val layer = decodeLayer(Reader(bytes, r.pos, r.pos + len))
                out[layer.name] = layer
                r.pos += len
            } else r.skip(key and 7)
        }
        return out
    }

    private class RawFeature(val type: Int, val tags: IntArray, val geom: IntArray)

    private fun decodeLayer(r: Reader): Layer {
        var name = ""
        var extent = 4096
        val keys = ArrayList<String>()
        val values = ArrayList<Any?>()
        val raw = ArrayList<RawFeature>()
        while (r.hasMore()) {
            val key = r.varint().toInt()
            when (key shr 3) {
                1 -> name = r.string()
                2 -> raw.add(decodeFeature(r.sub()))
                3 -> keys.add(r.string())
                4 -> values.add(decodeValue(r.sub()))
                5 -> extent = r.varint().toInt()
                else -> r.skip(key and 7)
            }
        }
        val layer = Layer(name, extent, keys, values)
        for (f in raw) layer.features.add(Feature(f.type, decodeGeometry(f.geom, f.type), f.tags, layer))
        return layer
    }

    private fun decodeFeature(r: Reader): RawFeature {
        var type = 0
        var tags = IntArray(0)
        var geom = IntArray(0)
        while (r.hasMore()) {
            val key = r.varint().toInt()
            when (key shr 3) {
                2 -> tags = r.packed()
                3 -> type = r.varint().toInt()
                4 -> geom = r.packed()
                else -> r.skip(key and 7)
            }
        }
        return RawFeature(type, tags, geom)
    }

    private fun decodeValue(r: Reader): Any? {
        var v: Any? = null
        while (r.hasMore()) {
            val key = r.varint().toInt()
            v = when (key shr 3) {
                1 -> r.string()
                2 -> java.lang.Float.intBitsToFloat(r.fixed32()).toDouble()
                3 -> java.lang.Double.longBitsToDouble(r.fixed64())
                4 -> r.varint()                                   // int64
                5 -> r.varint()                                   // uint64
                6 -> r.varint().let { (it ushr 1) xor -(it and 1) } // sint64 (zigzag)
                7 -> r.varint() != 0L
                else -> { r.skip(key and 7); v }
            }
        }
        return v
    }

    /** Command stream → parts. MoveTo starts a part; ClosePath is implicit for polygon rings. */
    internal fun decodeGeometry(cmds: IntArray, type: Int): List<IntArray> {
        val parts = ArrayList<IntArray>()
        var cur = IntArrayBuilder()
        var x = 0; var y = 0
        var i = 0
        while (i < cmds.size) {
            val cmdInt = cmds[i++]
            val id = cmdInt and 7
            val count = cmdInt ushr 3
            when (id) {
                1, 2 -> {                               // MoveTo / LineTo
                    repeat(count) {
                        if (i + 1 >= cmds.size) return parts.also { if (cur.size > 0) it.add(cur.build()) }
                        if (id == 1 && (type != GEOM_POINT) && cur.size > 0) { parts.add(cur.build()); cur = IntArrayBuilder() }
                        x += zigzag(cmds[i++]); y += zigzag(cmds[i++])
                        cur.add(x); cur.add(y)
                    }
                }
                7 -> {}                                  // ClosePath: rings are closed when drawn
            }
        }
        if (cur.size > 0) parts.add(cur.build())
        return parts
    }

    private fun zigzag(n: Int): Int = (n ushr 1) xor -(n and 1)

    private class IntArrayBuilder {
        private var a = IntArray(16)
        var size = 0; private set
        fun add(v: Int) { if (size == a.size) a = a.copyOf(size * 2); a[size++] = v }
        fun build(): IntArray = a.copyOf(size)
    }

    private class Reader(val buf: ByteArray, var pos: Int, val end: Int) {
        fun hasMore() = pos < end
        fun varint(): Long {
            var result = 0L; var shift = 0
            while (true) {
                val b = buf[pos++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b < 0x80) return result
                shift += 7
            }
        }
        fun fixed32(): Int {
            val v = (buf[pos].toInt() and 0xFF) or ((buf[pos + 1].toInt() and 0xFF) shl 8) or
                ((buf[pos + 2].toInt() and 0xFF) shl 16) or ((buf[pos + 3].toInt() and 0xFF) shl 24)
            pos += 4; return v
        }
        fun fixed64(): Long {
            val lo = fixed32().toLong() and 0xFFFFFFFFL
            val hi = fixed32().toLong() and 0xFFFFFFFFL
            return lo or (hi shl 32)
        }
        fun sub(): Reader { val len = varint().toInt(); val r = Reader(buf, pos, pos + len); pos += len; return r }
        fun string(): String { val len = varint().toInt(); val s = String(buf, pos, len, Charsets.UTF_8); pos += len; return s }
        fun packed(): IntArray {
            val r = sub()
            val out = IntArrayBuilder()
            while (r.hasMore()) out.add(r.varint().toInt())
            return out.build()
        }
        fun skip(wireType: Int) {
            when (wireType) {
                0 -> varint()
                1 -> pos += 8
                2 -> pos += varint().toInt()
                5 -> pos += 4
                else -> throw IllegalStateException("unsupported wire type $wireType")
            }
        }
    }
}
