package io.github.thinke.snaptv.core

import io.github.thinke.snaptv.core.codec.OggDemuxer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class OggTest {
    /** Builds one page holding [segments] of body (lacing values given explicitly). */
    private fun page(seq: Int, lacing: IntArray, body: ByteArray, granule: Long = 0, flags: Int = 0, serial: Int = 0x1234): ByteArray {
        val b = ByteBuffer.allocate(27 + lacing.size + body.size).order(ByteOrder.LITTLE_ENDIAN)
        b.put("OggS".toByteArray()).put(0).put(flags.toByte()).putLong(granule).putInt(serial).putInt(seq).putInt(0)
        b.put(lacing.size.toByte())
        lacing.forEach { b.put(it.toByte()) }
        b.put(body)
        val a = b.array()
        val crc = OggDemuxer.crc(a, 0, a.size)
        ByteBuffer.wrap(a).order(ByteOrder.LITTLE_ENDIAN).putInt(22, crc)
        return a
    }

    private fun lacing(size: Int, terminated: Boolean = true): IntArray {
        val full = size / 255
        val rest = size % 255
        return IntArray(full) { 255 } + if (terminated) intArrayOf(rest) else IntArray(0)
    }

    private fun bytes(n: Int, seed: Int) = ByteArray(n) { (it * 7 + seed).toByte() }

    private fun resource(name: String) = javaClass.getResourceAsStream("/ogg/$name")!!.readBytes()

    @Test
    fun crcMatchesLibogg() {
        // Every page libvorbis/ffmpeg wrote must pass; if our CRC were wrong nothing would come out.
        val d = OggDemuxer()
        val packets = d.push(resource("sine_vorbis.ogg"))
        assertEquals(0, d.corruptPages)
        assertTrue(packets.size > 10)
        assertEquals(1, packets[0].data[0].toInt())
        assertEquals(3, packets[1].data[0].toInt())
        assertEquals(5, packets[2].data[0].toInt())
    }

    @Test
    fun sameResultWhateverTheInputSplit() {
        val file = resource("sine_vorbis.ogg")
        val whole = OggDemuxer().push(file)
        for (step in intArrayOf(1, 7, 100, 4096)) {
            val d = OggDemuxer()
            val got = ArrayList<ByteArray>()
            var p = 0
            while (p < file.size) {
                val n = minOf(step, file.size - p)
                d.push(file, p, n).forEach { got += it.data }
                p += n
            }
            assertEquals(whole.size, got.size)
            whole.forEachIndexed { i, pk -> assertArrayEquals(pk.data, got[i]) }
        }
    }

    @Test
    fun granulePositionsOnLastPacketOfPage() {
        val packets = OggDemuxer().push(resource("sine_vorbis.ogg"))
        val audio = packets.drop(3).filter { it.granulePos >= 0 }
        assertTrue(audio.isNotEmpty())
        assertTrue(audio.zipWithNext().all { (a, b) -> b.granulePos >= a.granulePos })
        // 0.5 s at 48 kHz; the last page carries the exact end.
        assertEquals(24000L, audio.last().granulePos)
    }

    @Test
    fun packetsSpanningPagesAndExactMultiplesOf255() {
        val big = bytes(700, 1) // 255 + 255 + 190 across two pages
        val exact = bytes(510, 2) // needs a terminating 0 lacing value
        val small = bytes(3, 3)
        val p0 = page(0, intArrayOf(255, 255), big.copyOfRange(0, 510), granule = -1, flags = 2)
        val p1 = page(1, intArrayOf(190) + lacing(510) + intArrayOf(3), big.copyOfRange(510, 700) + exact + small, granule = 99, flags = 1)
        val packets = OggDemuxer().push(p0 + p1)
        assertEquals(3, packets.size)
        assertArrayEquals(big, packets[0].data)
        assertArrayEquals(exact, packets[1].data)
        assertArrayEquals(small, packets[2].data)
        assertEquals(-1L, packets[0].granulePos)
        assertEquals(99L, packets[2].granulePos)
    }

    @Test
    fun corruptPageIsSkippedAndTornPacketDropped() {
        val a = bytes(300, 4)
        val b = bytes(10, 5)
        val c = bytes(20, 6)
        val p0 = page(0, intArrayOf(10, 255), b + a.copyOfRange(0, 255), flags = 2)
        val p1 = page(1, intArrayOf(45), a.copyOfRange(255, 300), flags = 1)
        val p2 = page(2, intArrayOf(20), c)
        p1[p1.size - 1] = (p1[p1.size - 1] + 1).toByte()
        val d = OggDemuxer()
        val packets = d.push(p0 + p1 + p2)
        assertEquals(1, d.corruptPages)
        assertEquals(2, packets.size)
        assertArrayEquals(b, packets[0].data)
        assertArrayEquals(c, packets[1].data)
    }

    @Test
    fun continuationAfterLostPageIsNotGluedToOldPacket() {
        val a = bytes(300, 7)
        val c = bytes(30, 8)
        val p0 = page(0, intArrayOf(255), a.copyOfRange(0, 255), flags = 2)
        // page 1 (the end of a and the start of another packet) never arrives
        val p2 = page(2, intArrayOf(40, 30), bytes(40, 9) + c, flags = 1)
        val packets = OggDemuxer().push(p0 + p2)
        assertEquals(1, packets.size)
        assertArrayEquals(c, packets[0].data)
    }

    @Test
    fun garbageBeforeAndBetweenPages() {
        val x = bytes(5, 10)
        val y = bytes(6, 11)
        val out = ByteArrayOutputStream()
        out.write("junkOgg".toByteArray())
        out.write(page(0, intArrayOf(5), x, flags = 2))
        out.write(byteArrayOf(1, 2, 3))
        out.write(page(1, intArrayOf(6), y))
        val packets = OggDemuxer().push(out.toByteArray())
        assertEquals(2, packets.size)
        assertArrayEquals(x, packets[0].data)
        assertArrayEquals(y, packets[1].data)
    }
}
