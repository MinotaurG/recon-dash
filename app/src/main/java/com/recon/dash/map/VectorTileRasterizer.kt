package com.recon.dash.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.util.LruCache

/**
 * Rasterizes OpenMapTiles-schema vector tiles into 256 px bitmaps for the dash's Canvas map
 * renderer (which composites bitmap tiles). Zooms beyond the archive's max zoom are OVERZOOMED
 * from vector data — the ancestor tile's geometry is scaled up and re-drawn, so z17 is as crisp as
 * z14 (no blurry upscaled pixels).
 *
 * Palette matches MapRenderer's light Google-style look (the dash TFT reads best light in daylight).
 * No labels: the dash's own widgets show street names, and text at 526×300 is unreadable anyway.
 */
class VectorTileRasterizer(
    private val fetch: (z: Int, x: Int, y: Int) -> ByteArray?,
    private val maxZoom: Int,
) {
    companion object {
        const val TILE = 256
        private val LAND = Color.rgb(229, 227, 223)          // = MapRenderer bgColor
        private val GREEN = Color.rgb(200, 226, 196)
        private val RESIDENTIAL = Color.rgb(224, 222, 218)
        private val WATER = Color.rgb(170, 211, 237)
        private val BUILDING = Color.rgb(214, 211, 206)
        private val CASING = Color.rgb(196, 194, 190)
        private val MOTORWAY = Color.rgb(248, 205, 110)
        private val TRUNK = Color.rgb(251, 222, 150)
        private val ROAD = Color.WHITE
        private val RAIL = Color.rgb(180, 178, 175)
        private val BOUNDARY = Color.rgb(160, 150, 170)
    }

    // Decoded ancestor tiles, reused by all the overzoomed children drawn from them.
    private val decoded = LruCache<String, Map<String, MvtDecoder.Layer>>(24)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()

    /** Road class → (fill colour, fill width px at z14, draw casing). Widths scale with zoom. */
    private data class RoadStyle(val color: Int, val widthZ14: Float, val minZoom: Int)
    private val roadStyles = linkedMapOf(                   // drawn in this order (minor first)
        "service" to RoadStyle(ROAD, 1.2f, 14), "track" to RoadStyle(ROAD, 1.0f, 14),
        "minor" to RoadStyle(ROAD, 2.0f, 12),
        "tertiary" to RoadStyle(ROAD, 3.0f, 10), "secondary" to RoadStyle(ROAD, 3.5f, 9),
        "primary" to RoadStyle(ROAD, 4.5f, 7), "trunk" to RoadStyle(TRUNK, 5f, 5),
        "motorway" to RoadStyle(MOTORWAY, 5.5f, 4),
    )

    /** Render tile (z, x, y), or null if the archive has no data for it (e.g. outside India). */
    @Synchronized
    fun render(z: Int, x: Int, y: Int): Bitmap? {
        // Source tile: itself, or the ancestor at maxZoom when overzooming.
        val dz = (z - maxZoom).coerceAtLeast(0)
        val sz = z - dz
        val sx = x shr dz
        val sy = y shr dz
        val layers = layersFor(sz, sx, sy) ?: return null

        val bmp = Bitmap.createBitmap(TILE, TILE, Bitmap.Config.RGB_565)
        val c = Canvas(bmp)
        c.drawColor(LAND)

        // Map source-tile extent coords into this tile: scale by 2^dz and shift to our sub-square.
        val sub = 1 shl dz
        val offX = (x - (sx shl dz)).toFloat()
        val offY = (y - (sy shl dz)).toFloat()
        c.save()
        c.scale(sub.toFloat(), sub.toFloat())
        c.translate(-offX * TILE / sub, -offY * TILE / sub)
        // Canvas now has source-tile pixel space (0..TILE); each layer scales extent → TILE.
        val zoomScale = Math.pow(2.0, (z - 14).toDouble()).toFloat()  // line widths grow with zoom
        val pxPerSrc = sub.toFloat()                                  // canvas px per source px

        layers["landcover"]?.let { l ->
            fillWhere(c, l, GREEN) { it.str("class") in setOf("wood", "grass", "farmland", "wetland") }
        }
        layers["park"]?.let { fillWhere(c, it, GREEN) { true } }
        layers["landuse"]?.let { l ->
            fillWhere(c, l, RESIDENTIAL) { it.str("class") in setOf("residential", "suburb", "neighbourhood") }
        }
        layers["water"]?.let { fillWhere(c, it, WATER) { true } }
        layers["waterway"]?.let { l ->
            strokeWhere(c, l, WATER, 1.5f * zoomScale.coerceAtMost(4f) / pxPerSrc) { true }
        }
        if (z >= 15) layers["building"]?.let { fillWhere(c, it, BUILDING) { true } }
        layers["boundary"]?.let { l ->
            line.pathEffect = DashPathEffect(floatArrayOf(6f / pxPerSrc, 4f / pxPerSrc), 0f)
            strokeWhere(c, l, BOUNDARY, 1.2f / pxPerSrc) { (it.num("admin_level") ?: 99) <= 4 && it.num("maritime") != 1L }
            line.pathEffect = null
        }

        layers["transportation"]?.let { l ->
            line.pathEffect = DashPathEffect(floatArrayOf(5f / pxPerSrc, 4f / pxPerSrc), 0f)
            strokeWhere(c, l, RAIL, 1.2f / pxPerSrc) { z >= 12 && it.str("class") in setOf("rail", "transit") }
            line.pathEffect = null
            // Casings for every visible class first, then fills, so junctions merge cleanly.
            for ((cls, st) in roadStyles) {
                if (z < st.minZoom) continue
                val w = roadWidth(st.widthZ14, zoomScale) / pxPerSrc
                strokeWhere(c, l, CASING, w + 2f / pxPerSrc) { it.str("class") == cls }
            }
            for ((cls, st) in roadStyles) {
                if (z < st.minZoom) continue
                val w = roadWidth(st.widthZ14, zoomScale) / pxPerSrc
                strokeWhere(c, l, st.color, w) { it.str("class") == cls }
            }
        }
        c.restore()
        return bmp
    }

    /** Width grows ~1.6x per zoom level beyond 14 (capped), shrinks below it. */
    private fun roadWidth(w14: Float, zoomScale: Float): Float =
        (w14 * Math.pow(zoomScale.toDouble(), 0.7).toFloat()).coerceIn(0.6f, 26f)

    private fun layersFor(z: Int, x: Int, y: Int): Map<String, MvtDecoder.Layer>? {
        val key = "$z/$x/$y"
        decoded.get(key)?.let { return it }
        val bytes = fetch(z, x, y) ?: return null
        val layers = runCatching { MvtDecoder.decode(bytes) }.getOrNull() ?: return null
        decoded.put(key, layers)
        return layers
    }

    private inline fun fillWhere(c: Canvas, l: MvtDecoder.Layer, color: Int, pred: (MvtDecoder.Feature) -> Boolean) {
        val s = TILE.toFloat() / l.extent
        fill.color = color
        path.reset()
        // WINDING: MVT exterior rings are clockwise, holes counter-clockwise → holes stay open
        // while separate overlapping polygons merged into this one path still union correctly.
        path.fillType = Path.FillType.WINDING
        for (f in l.features) {
            if (f.type != MvtDecoder.GEOM_POLYGON || !pred(f)) continue
            for (ring in f.parts) addPart(ring, s, close = true)
        }
        c.drawPath(path, fill)
    }

    private inline fun strokeWhere(c: Canvas, l: MvtDecoder.Layer, color: Int, width: Float, pred: (MvtDecoder.Feature) -> Boolean) {
        val s = TILE.toFloat() / l.extent
        line.color = color
        line.strokeWidth = width
        path.reset()
        for (f in l.features) {
            if (f.type != MvtDecoder.GEOM_LINE || !pred(f)) continue
            for (part in f.parts) addPart(part, s, close = false)
        }
        c.drawPath(path, line)
    }

    private fun addPart(p: IntArray, s: Float, close: Boolean) {
        if (p.size < 4) return
        path.moveTo(p[0] * s, p[1] * s)
        var i = 2
        while (i + 1 < p.size) { path.lineTo(p[i] * s, p[i + 1] * s); i += 2 }
        if (close) path.close()
    }
}
