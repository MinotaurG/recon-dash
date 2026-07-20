package com.recon.dash

import com.recon.dash.dash.protocol.K1GPacket
import com.recon.dash.dash.protocol.Tlv
import com.recon.dash.dash.protocol.hexToBytes
import org.junit.Assert.*
import org.junit.Test

class K1GPacketTest {

    @Test
    fun `build produces valid K1G packet with correct length and magic`() {
        val tlv = K1GPacket.tlv(0x06, 0x06, 0x0E, 0x33, 0x34)
        val pkt = K1GPacket.build(tlv)

        // Outer length = first 2 bytes big-endian
        val outerLen = ((pkt[0].toInt() and 0xFF) shl 8) or (pkt[1].toInt() and 0xFF)
        assertEquals(pkt.size, outerLen)

        // K1G magic at offset 12
        assertEquals(0x4B, pkt[12].toInt() and 0xFF) // 'K'
        assertEquals(0x31, pkt[13].toInt() and 0xFF) // '1'
        assertEquals(0x47, pkt[14].toInt() and 0xFF) // 'G'
        assertEquals(0x20, pkt[15].toInt() and 0xFF) // ' '
    }

    @Test
    fun `build with two TLVs includes both in seg count`() {
        val pkt = K1GPacket.build(
            K1GPacket.tlv(0x05, 0x01, 0x41),
            K1GPacket.tlv(0x06, 0x05, 0x55),
        )
        val segCount = ((pkt[2].toInt() and 0xFF) shl 8) or (pkt[3].toInt() and 0xFF)
        assertEquals(3, segCount) // 1 fixed header + 2 TLVs
    }

    @Test
    fun `patchSeq sets the correct byte after magic`() {
        val pkt = K1GPacket.build(K1GPacket.tlv(0x06, 0x06, 0x01, 0x02, 0x03))
        val patched = K1GPacket.patchSeq(pkt, 0xAB)
        // Seq byte is at offset 16 (right after "K1G " at 12..15)
        assertEquals(0xAB.toByte(), patched[16])
    }

    @Test
    fun `parseIncoming extracts TLVs from dash packet format`() {
        // Simulate a dash→app packet: header(8 bytes) + one TLV (type=0x09, sub=0x00, len=1, val=0x05)
        val data = byteArrayOf(
            0x00, 0x0D, // outer len = 13
            0x00, 0x01, // seg count = 1
            0x00, 0x00, 0x00, 0x00, // ignored
            0x09, 0x00, 0x00, 0x01, 0x05, // TLV: type=09, sub=00, len=1, val=05
        )
        val tlvs = K1GPacket.parseIncoming(data)
        assertEquals(1, tlvs.size)
        assertEquals(0x09, tlvs[0].type)
        assertEquals(0x00, tlvs[0].sub)
        assertArrayEquals(byteArrayOf(0x05), tlvs[0].value)
    }

    @Test
    fun `parseIncoming handles multiple TLVs`() {
        val data = byteArrayOf(
            0x00, 0x15, // outer len
            0x00, 0x02, // seg count = 2
            0x00, 0x00, 0x00, 0x00, // ignored
            0x07, 0x00, 0x00, 0x02, 0xAA.toByte(), 0xBB.toByte(), // TLV1
            0x09, 0x06, 0x00, 0x01, 0x55, // TLV2
        )
        val tlvs = K1GPacket.parseIncoming(data)
        assertEquals(2, tlvs.size)
        assertEquals(0x07, tlvs[0].type)
        assertEquals(2, tlvs[0].value.size)
        assertEquals(0x09, tlvs[1].type)
        assertEquals(0x06, tlvs[1].sub)
    }

    @Test
    fun `parseIncoming with too-short data returns empty`() {
        val tlvs = K1GPacket.parseIncoming(byteArrayOf(0x00, 0x01, 0x00))
        assertTrue(tlvs.isEmpty())
    }

    @Test
    fun `hexToBytes converts correctly`() {
        val bytes = "4B 31 47 20".hexToBytes()
        assertArrayEquals(byteArrayOf(0x4B, 0x31, 0x47, 0x20), bytes)
    }

    @Test
    fun `hexToBytes handles no-space format`() {
        val bytes = "0016".hexToBytes()
        assertArrayEquals(byteArrayOf(0x00, 0x16), bytes)
    }
}
