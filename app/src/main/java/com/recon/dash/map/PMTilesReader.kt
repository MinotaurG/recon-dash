package com.recon.dash.map

import com.recon.dash.util.DebugLog
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/**
 * Reads individual tiles from a local PMTiles v3 archive.
 *
 * PMTiles is a single-file tile archive format that supports HTTP range requests
 * or local random-access reads. This implementation reads from a local file
 * using RandomAccessFile for zero-copy tile extraction.
 *
 * Format reference: https://github.com/protomaps/PMTiles/blob/main/spec/v3/spec.md
 */
class PMTilesReader(private val file: File) {

    companion object {
        private const val TAG = "PMTilesReader"
        private const val HEADER_SIZE = 127L
        private const val MAGIC = 0x4D50 // "PM" in little-endian
    }

    private var raf: RandomAccessFile? = null
    private var rootDirOffset: Long = 0
    private var rootDirLength: Long = 0
    private var tileDataOffset: Long = 0
    private var tileDataLength: Long = 0
    private var leafDirOffset: Long = 0
    private var leafDirLength: Long = 0
    private var tileType: Int = 0
    private var internalCompression: Int = 0
    private var tileCompression: Int = 0
    private var isOpen = false

    val isValid: Boolean get() = isOpen

    fun open(): Boolean {
        if (!file.exists()) return false
        try {
            val r = RandomAccessFile(file, "r")
            raf = r

            val header = ByteArray(HEADER_SIZE.toInt())
            r.readFully(header)
            val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)

            val magic = buf.short.toInt() and 0xFFFF
            if (magic != MAGIC) {
                DebugLog.w(TAG) { "Not a PMTiles file (magic=0x${magic.toString(16)})" }
                close()
                return false
            }

            val version = buf.get().toInt()
            if (version != 3) {
                DebugLog.w(TAG) { "Unsupported PMTiles version $version (need v3)" }
                close()
                return false
            }

            rootDirOffset = buf.long
            rootDirLength = buf.long
            // Skip JSON metadata offset + length
            buf.long; buf.long
            leafDirOffset = buf.long
            leafDirLength = buf.long
            tileDataOffset = buf.long
            tileDataLength = buf.long

            // Addressed tiles count (8 bytes) — skip
            buf.long

            // Tile entries count (8 bytes) — skip
            buf.long

            // Tile contents count (8 bytes) — skip
            buf.long

            // Clustered flag (1 byte)
            buf.get()

            internalCompression = buf.get().toInt() and 0xFF
            tileCompression = buf.get().toInt() and 0xFF
            tileType = buf.get().toInt() and 0xFF

            isOpen = true
            DebugLog.i(TAG) { "Opened ${file.name} — v$version, tileType=$tileType, tileCompress=$tileCompression" }
            return true
        } catch (e: Exception) {
            DebugLog.e(TAG, { "Failed to open PMTiles: ${e.message}" }, e)
            close()
            return false
        }
    }

    fun getTile(z: Int, x: Int, y: Int): ByteArray? {
        val r = raf ?: return null
        val tileId = zxyToTileId(z, x, y)

        val entry = findTileEntry(tileId, rootDirOffset, rootDirLength)
            ?: return null

        return try {
            val offset = tileDataOffset + entry.offset
            val raw = ByteArray(entry.length.toInt())
            synchronized(r) {
                r.seek(offset)
                r.readFully(raw)
            }
            decompress(raw, tileCompression)
        } catch (e: Exception) {
            DebugLog.w(TAG) { "Failed to read tile z=$z x=$x y=$y: ${e.message}" }
            null
        }
    }

    fun close() {
        runCatching { raf?.close() }
        raf = null
        isOpen = false
    }

    private data class TileEntry(val tileId: Long, val offset: Long, val length: Long, val runLength: Long)

    private fun findTileEntry(tileId: Long, dirOffset: Long, dirLength: Long): TileEntry? {
        val r = raf ?: return null
        val dirData = ByteArray(dirLength.toInt())
        synchronized(r) {
            r.seek(dirOffset)
            r.readFully(dirData)
        }
        val decompressed = decompress(dirData, internalCompression)
        val entries = parseDirectory(decompressed)

        for (entry in entries) {
            if (entry.runLength == 0L) {
                // Leaf directory reference
                if (tileId >= entry.tileId && tileId < entry.tileId + 1) {
                    val leafOff = leafDirOffset + entry.offset
                    return findTileEntry(tileId, leafOff, entry.length)
                }
            } else {
                if (tileId >= entry.tileId && tileId < entry.tileId + entry.runLength) {
                    val indexInRun = tileId - entry.tileId
                    return if (entry.runLength == 1L) entry
                    else entry.copy(offset = entry.offset + indexInRun * (entry.length / entry.runLength))
                }
            }
        }
        return null
    }

    private fun parseDirectory(data: ByteArray): List<TileEntry> {
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val entries = ArrayList<TileEntry>()
        val numEntries = readVarint(buf)

        val tileIds = LongArray(numEntries.toInt())
        var lastId = 0L
        for (i in tileIds.indices) {
            val delta = readVarint(buf)
            lastId += delta
            tileIds[i] = lastId
        }

        val runLengths = LongArray(numEntries.toInt())
        for (i in runLengths.indices) {
            runLengths[i] = readVarint(buf)
        }

        val lengths = LongArray(numEntries.toInt())
        for (i in lengths.indices) {
            lengths[i] = readVarint(buf)
        }

        val offsets = LongArray(numEntries.toInt())
        var lastOffset = 0L
        for (i in offsets.indices) {
            val v = readVarint(buf)
            offsets[i] = if (v == 0L && i > 0) lastOffset + lengths[i - 1] else v
            lastOffset = offsets[i]
        }

        for (i in 0 until numEntries.toInt()) {
            entries.add(TileEntry(tileIds[i], offsets[i], lengths[i], runLengths[i]))
        }
        return entries
    }

    private fun readVarint(buf: ByteBuffer): Long {
        var result = 0L
        var shift = 0
        while (buf.hasRemaining()) {
            val b = buf.get().toLong() and 0xFF
            result = result or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0L) break
            shift += 7
        }
        return result
    }

    private fun decompress(data: ByteArray, compression: Int): ByteArray = when (compression) {
        0 -> data // none
        1 -> data // none
        2 -> GZIPInputStream(data.inputStream()).readBytes() // gzip
        3 -> InflaterInputStream(data.inputStream(), Inflater(true)).readBytes() // brotli fallback to raw deflate
        else -> data
    }

    /**
     * Convert z/x/y to a Hilbert tile ID (PMTiles v3 uses Hilbert curve ordering).
     * TMS y-flip is applied: PMTiles uses TMS convention (y=0 at bottom).
     */
    private fun zxyToTileId(z: Int, x: Int, y: Int): Long {
        if (z == 0) return 0L
        val dim = 1 shl z
        val tmsY = dim - 1 - y // flip for TMS
        val base = (1L until z.toLong()).fold(0L) { acc, i -> acc + (1L shl (2 * i.toInt())) }
        return base + xyToHilbert(x, tmsY, z)
    }

    private fun xyToHilbert(x: Int, y: Int, order: Int): Long {
        var rx: Int
        var ry: Int
        var d = 0L
        var cx = x
        var cy = y
        var s = order - 1
        while (s >= 0) {
            val n = 1 shl s
            rx = if (cx and n != 0) 1 else 0
            ry = if (cy and n != 0) 1 else 0
            d += (s.toLong() * 2).let { (3L * rx.toLong()) xor ry.toLong() } shl (2 * s)

            // Rotate
            if (ry == 0) {
                if (rx == 1) {
                    cx = n - 1 - cx
                    cy = n - 1 - cy
                }
                val temp = cx; cx = cy; cy = temp
            }
            s--
        }
        return d
    }
}
