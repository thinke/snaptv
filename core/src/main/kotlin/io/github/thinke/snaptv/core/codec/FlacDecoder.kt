package io.github.thinke.snaptv.core.codec

/**
 * A small, allocation-light FLAC frame decoder.
 *
 * snapserver's FLAC encoder hands out whole frames per wire chunk (libFLAC's write callback
 * is per frame), and the codec header is the "fLaC" marker plus metadata blocks. So we only
 * need STREAMINFO for defaults and a frame decoder, with no stream-level state across chunks.
 */
class FlacDecoder : Decoder {
    private var streamRate = 0
    private var streamBits = 0
    private var streamChannels = 0

    // Reused across frames; grown on demand.
    private var channelBuf = Array(2) { IntArray(4096) }

    override fun setHeader(payload: ByteArray): SampleFormat {
        if (payload.size < 4 || String(payload, 0, 4, Charsets.US_ASCII) != "fLaC") throw IllegalArgumentException("not a FLAC header")
        var pos = 4
        while (pos + 4 <= payload.size) {
            val blockHeader = payload[pos].toInt() and 0xff
            val last = blockHeader and 0x80 != 0
            val type = blockHeader and 0x7f
            val len = ((payload[pos + 1].toInt() and 0xff) shl 16) or ((payload[pos + 2].toInt() and 0xff) shl 8) or (payload[pos + 3].toInt() and 0xff)
            if (type == 0) {
                val r = BitReader(payload, pos + 4, pos + 4 + len)
                r.skip(16 + 16 + 24 + 24)
                streamRate = r.readBits(20)
                streamChannels = r.readBits(3) + 1
                streamBits = r.readBits(5) + 1
                return SampleFormat(streamRate, streamBits, streamChannels)
            }
            if (last) break
            pos += 4 + len
        }
        throw IllegalArgumentException("FLAC header without STREAMINFO")
    }

    override fun decode(payload: ByteArray): ShortArray {
        val frames = ArrayList<ShortArray>(2)
        val r = BitReader(payload, 0, payload.size)
        var total = 0
        while (r.bytesRemaining() >= 2) {
            val pcm = decodeFrame(r)
            frames += pcm
            total += pcm.size
        }
        if (frames.size == 1) return frames[0]
        val out = ShortArray(total)
        var o = 0
        for (f in frames) {
            f.copyInto(out, o)
            o += f.size
        }
        return out
    }

    /**
     * Splits FLAC audio (everything after the header) into single frames, the way snapserver's
     * encoder hands them out. Needs [setHeader] first. Used to feed other decoders in tests.
     */
    fun splitFrames(audio: ByteArray): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        val r = BitReader(audio, 0, audio.size)
        var start = 0
        while (r.bytesRemaining() >= 2) {
            decodeFrame(r)
            val end = audio.size - r.bytesRemaining()
            out += audio.copyOfRange(start, end)
            start = end
        }
        return out
    }

    private fun decodeFrame(r: BitReader): ShortArray {
        val sync = r.readBits(14)
        if (sync != 0x3ffe) throw FlacException("lost frame sync")
        r.skip(2) // reserved + blocking strategy
        val bsCode = r.readBits(4)
        val srCode = r.readBits(4)
        val chAssign = r.readBits(4)
        val ssCode = r.readBits(3)
        r.skip(1)
        skipUtf8Number(r)

        val blockSize = when (bsCode) {
            1 -> 192
            in 2..5 -> 576 shl (bsCode - 2)
            6 -> r.readBits(8) + 1
            7 -> r.readBits(16) + 1
            in 8..15 -> 256 shl (bsCode - 8)
            else -> throw FlacException("reserved block size")
        }
        when (srCode) {
            12 -> r.skip(8)
            13, 14 -> r.skip(16)
            15 -> throw FlacException("invalid sample rate code")
        }
        val bps = when (ssCode) {
            0 -> streamBits
            1 -> 8
            2 -> 12
            4 -> 16
            5 -> 20
            6 -> 24
            7 -> 32
            else -> throw FlacException("reserved sample size")
        }
        r.skip(8) // CRC-8

        val channels = if (chAssign < 8) chAssign + 1 else if (chAssign <= 10) 2 else throw FlacException("reserved channel assignment")
        if (channelBuf.size < channels || channelBuf[0].size < blockSize) {
            channelBuf = Array(maxOf(channels, channelBuf.size)) { IntArray(maxOf(blockSize, channelBuf[0].size)) }
        }
        for (ch in 0 until channels) {
            val sideChannel = (chAssign == 8 && ch == 1) || (chAssign == 9 && ch == 0) || (chAssign == 10 && ch == 1)
            decodeSubframe(r, channelBuf[ch], blockSize, if (sideChannel) bps + 1 else bps)
        }
        r.alignToByte()
        r.skip(16) // CRC-16

        val a = channelBuf[0]
        val b = if (channels > 1) channelBuf[1] else a
        when (chAssign) {
            8 -> for (i in 0 until blockSize) b[i] = a[i] - b[i] // left/side
            9 -> for (i in 0 until blockSize) a[i] += b[i] // side/right
            10 -> for (i in 0 until blockSize) { // mid/side
                val side = b[i]
                val mid = (a[i] shl 1) or (side and 1)
                a[i] = (mid + side) shr 1
                b[i] = (mid - side) shr 1
            }
        }

        val out = ShortArray(blockSize * channels)
        val shift = bps - 16
        for (ch in 0 until channels) {
            val src = channelBuf[ch]
            var o = ch
            if (shift >= 0) {
                for (i in 0 until blockSize) { out[o] = (src[i] shr shift).toShort(); o += channels }
            } else {
                for (i in 0 until blockSize) { out[o] = (src[i] shl -shift).toShort(); o += channels }
            }
        }
        return out
    }

    private fun skipUtf8Number(r: BitReader) {
        val first = r.readBits(8)
        var extra = 0
        var mask = 0x80
        while (first and mask != 0 && mask != 0) { extra++; mask = mask shr 1 }
        if (extra > 0) extra-- // first byte counts itself
        r.skip(8 * extra)
    }

    private fun decodeSubframe(r: BitReader, s: IntArray, n: Int, bitsIn: Int) {
        r.skip(1)
        val type = r.readBits(6)
        var bits = bitsIn
        var wasted = 0
        if (r.readBits(1) == 1) {
            wasted = r.readUnary() + 1
            bits -= wasted
        }
        when {
            type == 0 -> {
                val v = r.readSigned(bits)
                s.fill(v, 0, n)
            }
            type == 1 -> for (i in 0 until n) s[i] = r.readSigned(bits)
            type in 8..12 -> {
                val order = type and 7
                for (i in 0 until order) s[i] = r.readSigned(bits)
                readResidual(r, s, n, order)
                restoreFixed(s, n, order)
            }
            type >= 32 -> {
                val order = (type and 31) + 1
                for (i in 0 until order) s[i] = r.readSigned(bits)
                val precision = r.readBits(4) + 1
                if (precision == 16) throw FlacException("invalid LPC precision")
                val shift = r.readSigned(5)
                if (shift < 0) throw FlacException("negative LPC shift")
                val coefs = IntArray(order) { r.readSigned(precision) }
                readResidual(r, s, n, order)
                for (i in order until n) {
                    var sum = 0L
                    for (j in 0 until order) sum += coefs[j].toLong() * s[i - 1 - j]
                    s[i] += (sum shr shift).toInt()
                }
            }
            else -> throw FlacException("reserved subframe type $type")
        }
        if (wasted > 0) for (i in 0 until n) s[i] = s[i] shl wasted
    }

    private fun readResidual(r: BitReader, s: IntArray, n: Int, order: Int) {
        val method = r.readBits(2)
        if (method > 1) throw FlacException("reserved residual coding method")
        val paramBits = if (method == 0) 4 else 5
        val escape = if (method == 0) 15 else 31
        val partitionOrder = r.readBits(4)
        val partitions = 1 shl partitionOrder
        val partSize = n shr partitionOrder
        var i = order
        for (p in 0 until partitions) {
            val count = if (p == 0) partSize - order else partSize
            val param = r.readBits(paramBits)
            if (param == escape) {
                val raw = r.readBits(5)
                for (k in 0 until count) s[i++] = if (raw == 0) 0 else r.readSigned(raw)
            } else {
                for (k in 0 until count) {
                    val q = r.readUnary()
                    val u = (q shl param) or (if (param > 0) r.readBits(param) else 0)
                    s[i++] = (u ushr 1) xor -(u and 1)
                }
            }
        }
    }

    private fun restoreFixed(s: IntArray, n: Int, order: Int) {
        when (order) {
            1 -> for (i in 1 until n) s[i] += s[i - 1]
            2 -> for (i in 2 until n) s[i] += 2 * s[i - 1] - s[i - 2]
            3 -> for (i in 3 until n) s[i] += 3 * s[i - 1] - 3 * s[i - 2] + s[i - 3]
            4 -> for (i in 4 until n) s[i] += 4 * s[i - 1] - 6 * s[i - 2] + 4 * s[i - 3] - s[i - 4]
        }
    }
}

class FlacException(message: String) : Exception(message)

/** MSB-first bit reader over a byte range. */
internal class BitReader(private val data: ByteArray, private var pos: Int, private val end: Int) {
    private var buf = 0L
    private var count = 0

    fun bytesRemaining(): Int = end - pos + count / 8

    private fun refill() {
        if (pos >= end) throw FlacException("unexpected end of data")
        buf = (buf shl 8) or (data[pos++].toLong() and 0xff)
        count += 8
    }

    /** Reads up to 32 bits as an unsigned value (fits in Int for n <= 31). */
    fun readBits(n: Int): Int {
        if (n == 0) return 0
        while (count < n) refill()
        count -= n
        val v = (buf ushr count) and ((1L shl n) - 1)
        buf = buf and ((1L shl count) - 1)
        return v.toInt()
    }

    fun readSigned(n: Int): Int {
        if (n == 0) return 0
        val v = readBits(n).toLong()
        return ((v shl (64 - n)) shr (64 - n)).toInt()
    }

    fun skip(n: Int) {
        var left = n
        while (left > 24) { readBits(24); left -= 24 }
        readBits(left)
    }

    /** Counts zero bits up to and including the terminating one bit. */
    fun readUnary(): Int {
        var zeros = 0
        while (true) {
            if (count == 0) refill()
            if (buf == 0L) {
                zeros += count
                count = 0
                continue
            }
            val highest = 63 - java.lang.Long.numberOfLeadingZeros(buf)
            zeros += count - 1 - highest
            count = highest
            buf = buf and ((1L shl count) - 1)
            return zeros
        }
    }

    fun alignToByte() {
        val drop = count % 8
        count -= drop
        buf = buf and ((1L shl count) - 1)
    }
}
