package com.recon.dash.map

import com.recon.dash.util.DebugLog
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream

/**
 * Reads individual tiles from a local [PMTiles v3](https://github.com/protomaps/PMTiles) archive.
 *
 * Spec-verified port of the standalone `pmtiles-kotlin` library
 * (https://github.com/MinotaurG/pmtiles-kotlin), which is tested against the official Protomaps
 * fixtures + golden Hilbert tile-id values. The earlier version of this file had never worked
 * against a real archive: it read a 2-byte magic (spec magic is the 7-byte string "PMTiles"),
 * applied a TMS y-flip PMTiles does not use, had an off-by-one Hilbert base, and mis-decoded
 * directory offsets. All fixed here; see the library repo for the test suite.
 *
 * Format reference: https://github.com/protomaps/PMTiles/blob/main/spec/v3/spec.md
 */
class PMTilesReader(private val file: File) {

    companion object {
        private const val TAG = "PMTilesReader"
        private const val HEADER_SIZE = 127
        private val MAGIC = byteArrayOf(0x50, 0x4D, 0x54, 0x69, 0x6C, 0x65, 0x73) // "PMTiles"

        private const val COMPRESSION_NONE = 1
        private const val COMPRESSION_GZIP = 2
        private const val COMPRESSION_BROTLI = 3
        private const val COMPRESSION_ZSTD = 4
    }

    private var raf: RandomAccessFile? = null
    private var rootDirOffset = 0L
    private var rootDirLength = 0L
    private var leafDirOffset = 0L
    private var tileDataOffset = 0L
    private var internalCompression = 0
    private var tileCompression = 0
    private var tileType = 0
    private var isOpen = false

    val isValid: Boolean get() = isOpen

    fun open(): Boolean {
        if (!file.exists()) return false
        try {
            val r = RandomAccessFile(file, "r")
            raf = r
            val header = ByteArray(HEADER_SIZE)
            r.readFully(header)
            val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)

            val magic = ByteArray(7).also { buf.get(it) }
            if (!magic.contentEquals(MAGIC)) {
                DebugLog.w(TAG) { "Not a PMTiles file (bad magic)" }
                close(); return false
            }
            val version = buf.get().toInt()             // offset 7
            if (version != 3) {
                DebugLog.w(TAG) { "Unsupported PMTiles version $version (need v3)" }
                close(); return false
            }

            rootDirOffset = buf.long                     // 8
            rootDirLength = buf.long                     // 16
            buf.long; buf.long                           // 24/32 metadata (unused)
            leafDirOffset = buf.long                     // 40
            buf.long                                     // 48 leaf dir length (unused)
            tileDataOffset = buf.long                    // 56
            buf.long                                     // 64 tile data length (unused)
            buf.long; buf.long; buf.long                 // 72/80/88 counts
            buf.get()                                    // 96 clustered flag
            internalCompression = buf.get().toInt() and 0xFF  // 97
            tileCompression = buf.get().toInt() and 0xFF       // 98
            tileType = buf.get().toInt() and 0xFF              // 99

            isOpen = true
            DebugLog.i(TAG) { "Opened ${file.name} — v$version, tileType=$tileType, tileCompress=$tileCompression" }
            return true
        } catch (e: Exception) {
            DebugLog.e(TAG, { "Failed to open PMTiles: ${e.message}" }, e)
            close(); return false
        }
    }

    fun getTile(z: Int, x: Int, y: Int): ByteArray? {
        raf ?: return null
        if (!isOpen) return null
        val tileId = PMTileId.zxyToTileId(z, x, y)
        val entry = findTileEntry(tileId, rootDirOffset, rootDirLength, depth = 0) ?: return null
        return try {
            val raw = readAt(tileDataOffset + entry.offset, entry.length.toInt())
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

    private fun findTileEntry(tileId: Long, dirOffset: Long, dirLength: Long, depth: Int): TileEntry? {
        if (depth > 3) return null
        val dirData = decompress(readAt(dirOffset, dirLength.toInt()), internalCompression)
        val entries = parseDirectory(dirData)
        val entry = findInEntries(entries, tileId) ?: return null
        return if (entry.runLength == 0L) {
            findTileEntry(tileId, leafDirOffset + entry.offset, entry.length, depth + 1)
        } else entry
    }

    private fun findInEntries(entries: List<TileEntry>, tileId: Long): TileEntry? {
        var lo = 0
        var hi = entries.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val e = entries[mid]
            when {
                tileId < e.tileId -> hi = mid - 1
                e.runLength != 0L && tileId >= e.tileId + e.runLength -> lo = mid + 1
                else -> return e
            }
        }
        if (hi >= 0) {
            val e = entries[hi]
            if (e.runLength == 0L) return e
        }
        return null
    }

    private fun parseDirectory(data: ByteArray): List<TileEntry> {
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val n = readVarint(buf).toInt()
        val tileIds = LongArray(n)
        var last = 0L
        for (i in 0 until n) { last += readVarint(buf); tileIds[i] = last }
        val runLengths = LongArray(n) { readVarint(buf) }
        val lengths = LongArray(n) { readVarint(buf) }
        val offsets = LongArray(n)
        for (i in 0 until n) {
            val v = readVarint(buf)
            offsets[i] = if (v == 0L && i > 0) offsets[i - 1] + lengths[i - 1] else v - 1
        }
        return List(n) { TileEntry(tileIds[it], offsets[it], lengths[it], runLengths[it]) }
    }

    private fun readAt(offset: Long, len: Int): ByteArray {
        val r = raf ?: throw IllegalStateException("closed")
        val out = ByteArray(len)
        synchronized(r) { r.seek(offset); r.readFully(out) }
        return out
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
        COMPRESSION_NONE, 0 -> data
        COMPRESSION_GZIP -> GZIPInputStream(data.inputStream()).use { it.readBytes() }
        COMPRESSION_BROTLI, COMPRESSION_ZSTD ->
            throw UnsupportedOperationException("PMTiles: compression id $compression not supported")
        else -> data
    }
}
