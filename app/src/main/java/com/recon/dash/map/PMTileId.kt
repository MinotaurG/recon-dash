package com.recon.dash.map

/**
 * ZXY -> PMTiles Hilbert tile id (spec v3). Spec-verified port from the standalone `pmtiles-kotlin`
 * library (https://github.com/MinotaurG/pmtiles-kotlin); see there for the golden-value tests.
 *
 * A tile id numbers the whole pyramid: the base offset for all zoom levels below z, plus the tile's
 * position along the Hilbert curve at zoom z. XYZ / slippy-map convention (y=0 at top); NO TMS flip.
 */
object PMTileId {

    private const val MAX_ZOOM = 31

    /** Tiles in all zooms below [z]: sum_{i=0}^{z-1} 4^i = (4^z - 1) / 3. */
    fun baseOffset(z: Int): Long = ((1L shl (z * 2)) - 1L) / 3L

    fun zxyToTileId(z: Int, x: Int, y: Int): Long {
        require(z in 0..MAX_ZOOM) { "zoom $z out of range" }
        if (z == 0) return 0L
        val dim = 1 shl z
        require(x in 0 until dim && y in 0 until dim) { "tile $x/$y out of range at zoom $z" }
        return baseOffset(z) + hilbertXYToD(dim, x, y)
    }

    private fun hilbertXYToD(n: Int, x: Int, y: Int): Long {
        var d = 0L
        var cx = x
        var cy = y
        var s = n / 2
        while (s > 0) {
            val rx = if (cx and s > 0) 1 else 0
            val ry = if (cy and s > 0) 1 else 0
            d += s.toLong() * s.toLong() * ((3 * rx) xor ry)
            if (ry == 0) {
                if (rx == 1) { cx = n - 1 - cx; cy = n - 1 - cy }
                val t = cx; cx = cy; cy = t
            }
            s = s shr 1
        }
        return d
    }
}
