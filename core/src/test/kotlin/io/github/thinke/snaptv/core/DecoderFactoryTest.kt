package io.github.thinke.snaptv.core

import io.github.thinke.snaptv.core.codec.Decoder
import io.github.thinke.snaptv.core.codec.DecoderFactory
import io.github.thinke.snaptv.core.codec.FlacDecoder
import io.github.thinke.snaptv.core.codec.PcmDecoder
import io.github.thinke.snaptv.core.codec.SampleFormat
import io.github.thinke.snaptv.core.codec.UnsupportedCodecException
import io.github.thinke.snaptv.core.protocol.MessageType
import io.github.thinke.snaptv.core.protocol.MessageWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DecoderFactoryTest {
    @Test
    fun defaultFactoryKnowsFlacAndPcmOnly() {
        assertTrue(DecoderFactory.Default.create("flac") is FlacDecoder)
        assertTrue(DecoderFactory.Default.create("pcm") is PcmDecoder)
        for (codec in listOf("opus", "ogg", "null")) {
            val e = assertThrows(UnsupportedCodecException::class.java) { DecoderFactory.Default.create(codec) }
            assertEquals(codec, e.codec)
        }
    }

    /** Returns each chunk one call late, like a MediaCodec with one buffer of latency. */
    private class LateDecoder : Decoder {
        var held: ShortArray? = null
        var closed = CountDownLatch(1)
        override var carriedFrames = 0
        override fun setHeader(payload: ByteArray) = SampleFormat(48000, 16, 1)
        override fun decode(payload: ByteArray): ShortArray {
            val value = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).getShort().toInt()
            val now = ShortArray(480) { (value + it).toShort() }
            val out = held ?: ShortArray(0)
            held = now
            carriedFrames = out.size
            return out
        }
        override fun close() = closed.countDown()
    }

    private fun codecHeader(codec: String): ByteArray {
        val c = codec.toByteArray()
        return ByteBuffer.allocate(8 + c.size).order(ByteOrder.LITTLE_ENDIAN).putInt(c.size).put(c).putInt(0).array()
    }

    private fun wireChunk(tsUs: Long, value: Int): ByteArray = ByteBuffer.allocate(8 + 4 + 2).order(ByteOrder.LITTLE_ENDIAN)
        .putInt((tsUs / 1_000_000).toInt()).putInt((tsUs % 1_000_000).toInt()).putInt(2).putShort(value.toShort()).array()

    @Test
    fun engineUsesFactoryAndStampsCarriedAudioAtItsOwnTime() {
        val decoders = CopyOnWriteArrayList<LateDecoder>()
        val codecs = CopyOnWriteArrayList<String>()
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val sent = CountDownLatch(1)
        val serverThread = Thread {
            server.accept().use { s ->
                val o = s.getOutputStream()
                MessageWriter.write(o, MessageType.CODEC_HEADER, 0, 0, codecHeader("opus"))
                // chunks 10 ms apart, first sample value 1000 * (index + 1)
                for (k in 0 until 4) MessageWriter.write(o, MessageType.WIRE_CHUNK, 0, 0, wireChunk(5_000_000L + k * 10_000L, (k + 1) * 1000))
                o.flush()
                sent.countDown()
                Thread.sleep(2000)
            }
        }.apply { isDaemon = true; start() }

        val engine = SnapEngine(
            ClientIdentity("test", "test", "test", "test"),
            object : SnapListener {},
            decoders = { codec -> codecs += codec; LateDecoder().also { decoders += it } },
        )
        try {
            engine.start(InetAddress.getLoopbackAddress().hostAddress, server.localPort)
            assertTrue(sent.await(5, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + 5_000_000_000L
            while ((engine.stats()?.queuedMs ?: 0) < 30 && System.nanoTime() < deadline) Thread.sleep(10)
            assertEquals(listOf("opus"), codecs)
            val b = engine.buffer!!
            // Chunk 0 came out with chunk 1's call; it must still start at chunk 0's timestamp.
            val out = ShortArray(1)
            assertTrue(b.read(out, 1, 5_000_000L))
            assertEquals(1000, out[0].toInt())
        } finally {
            engine.stop()
            server.close()
            serverThread.interrupt()
        }
        assertTrue("decoder closed at session end", decoders.single().closed.await(2, TimeUnit.SECONDS))
    }
}
