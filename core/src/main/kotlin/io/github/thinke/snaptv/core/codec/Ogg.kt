package io.github.thinke.snaptv.core.codec

import java.io.ByteArrayOutputStream

/** One logical-stream packet. [granulePos] is the page's value if the packet ends that page, else -1. */
class OggPacket(val data: ByteArray, val granulePos: Long)

/**
 * Splits an Ogg byte stream into packets, like libogg's ogg_sync_pageout + ogg_stream_packetout.
 *
 * snapserver's `ogg` codec sends the Vorbis header pages as the codec header and then whole
 * pages per wire chunk, but this does not rely on that: input may be split anywhere, packets
 * may span pages, and pages with a bad CRC are skipped by resyncing on the next "OggS". A
 * packet that loses a page (bad CRC or sequence gap) is dropped rather than returned torn.
 */
class OggDemuxer {
    private var buf = ByteArray(8192)
    private var len = 0
    private val partial = ByteArrayOutputStream()
    private var inFlight = false
    private var serial = 0
    private var lastSeq = -1L

    /** Pages rejected for a bad CRC or version. */
    var corruptPages = 0
        private set

    fun push(data: ByteArray, off: Int = 0, count: Int = data.size): List<OggPacket> {
        if (len + count > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, len + count))
        System.arraycopy(data, off, buf, len, count)
        len += count
        val out = ArrayList<OggPacket>()
        var pos = 0
        while (true) {
            val start = findCapture(pos)
            if (start < 0) {
                // keep a possible partial "OggS" at the end
                pos = maxOf(pos, len - 3)
                break
            }
            pos = start
            if (len - pos < HEADER) break
            val segments = buf[pos + 26].toInt() and 0xff
            if (len - pos < HEADER + segments) break
            var bodyLen = 0
            for (i in 0 until segments) bodyLen += buf[pos + HEADER + i].toInt() and 0xff
            val pageLen = HEADER + segments + bodyLen
            if (len - pos < pageLen) break
            if (buf[pos + 4].toInt() != 0 || crc(buf, pos, pageLen) != le32(pos + 22)) {
                corruptPages++
                pos++
                continue
            }
            page(pos, segments, out)
            pos += pageLen
        }
        System.arraycopy(buf, pos, buf, 0, len - pos)
        len -= pos
        return out
    }

    private fun page(pos: Int, segments: Int, out: MutableList<OggPacket>) {
        val flags = buf[pos + 5].toInt()
        val granule = (le32(pos + 6).toLong() and 0xffffffffL) or (le32(pos + 10).toLong() shl 32)
        val pageSerial = le32(pos + 14)
        val seq = le32(pos + 18).toLong() and 0xffffffffL
        // A new stream, a lost page, or a fresh packet start all end whatever was in flight.
        val gap = flags and FLAG_BOS != 0 || pageSerial != serial || (lastSeq >= 0 && seq != lastSeq + 1)
        serial = pageSerial
        lastSeq = seq
        val continued = flags and FLAG_CONTINUED != 0
        if (gap || !continued) {
            partial.reset()
            inFlight = false
        }
        // A continued page without the start of its packet: skip that packet's tail.
        var skipping = continued && !inFlight
        var body = pos + HEADER + segments
        var lastComplete = -1
        for (i in 0 until segments) {
            val seg = buf[pos + HEADER + i].toInt() and 0xff
            if (!skipping) partial.write(buf, body, seg)
            body += seg
            if (seg < 255) {
                if (!skipping) {
                    out += OggPacket(partial.toByteArray(), -1)
                    lastComplete = out.size - 1
                }
                partial.reset()
                skipping = false
            }
        }
        inFlight = !skipping && partial.size() > 0
        if (lastComplete >= 0) out[lastComplete] = OggPacket(out[lastComplete].data, granule)
    }

    private fun findCapture(from: Int): Int {
        var i = from
        while (i + 4 <= len) {
            if (buf[i] == 'O'.code.toByte() && buf[i + 1] == 'g'.code.toByte() && buf[i + 2] == 'g'.code.toByte() && buf[i + 3] == 'S'.code.toByte()) return i
            i++
        }
        return -1
    }

    private fun le32(p: Int) = (buf[p].toInt() and 0xff) or ((buf[p + 1].toInt() and 0xff) shl 8) or
        ((buf[p + 2].toInt() and 0xff) shl 16) or ((buf[p + 3].toInt() and 0xff) shl 24)

    companion object {
        private const val HEADER = 27
        private const val FLAG_CONTINUED = 0x01
        private const val FLAG_BOS = 0x02

        private val CRC_TABLE = IntArray(256) { n ->
            var r = n shl 24
            repeat(8) { r = if (r and 0x80000000.toInt() != 0) (r shl 1) xor 0x04c11db7 else r shl 1 }
            r
        }

        /** Ogg's CRC-32 (poly 0x04c11db7, not reflected) over a page with its CRC field taken as zero. */
        fun crc(b: ByteArray, off: Int, count: Int): Int {
            var c = 0
            for (i in 0 until count) {
                val v = if (i in 22..25) 0 else b[off + i].toInt() and 0xff
                c = (c shl 8) xor CRC_TABLE[((c ushr 24) xor v) and 0xff]
            }
            return c
        }
    }
}
