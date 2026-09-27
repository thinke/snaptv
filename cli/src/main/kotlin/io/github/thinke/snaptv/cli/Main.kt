package io.github.thinke.snaptv.cli

import io.github.thinke.snaptv.core.ClientIdentity
import io.github.thinke.snaptv.core.ConnectionState
import io.github.thinke.snaptv.core.MonotonicClock
import io.github.thinke.snaptv.core.ServerSettings
import io.github.thinke.snaptv.core.SnapEngine
import io.github.thinke.snaptv.core.SnapListener
import io.github.thinke.snaptv.core.codec.SampleFormat
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine

/**
 * Desktop harness for the core engine.
 *
 *   cli <host> [--port 1704] [--seconds N] [--play] [--wav out.wav] [--id ID]
 *
 * Without --play it simulates an output device with a fixed 100 ms buffer, which is enough to
 * exercise time sync and the sync buffer against a real server.
 */
fun main(args: Array<String>) {
    val host = args.firstOrNull { !it.startsWith("--") } ?: error("usage: cli <host> [--port P] [--seconds N] [--play] [--wav file] [--id ID]")
    fun opt(name: String) = args.indexOf(name).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
    val port = opt("--port")?.toInt() ?: 1704
    val seconds = opt("--seconds")?.toInt() ?: 0
    val play = "--play" in args
    val wav = opt("--wav")?.let { WavWriter(File(it)) }
    val id = opt("--id") ?: "snaptv-cli"

    val clock = MonotonicClock.System
    var format: SampleFormat? = null
    val engine = SnapEngine(
        ClientIdentity(id = id, hostName = "snaptv-cli", os = System.getProperty("os.name"), arch = System.getProperty("os.arch"), clientName = "SnapTV-cli"),
        object : SnapListener {
            override fun onState(state: ConnectionState) = println("state: $state")
            override fun onFormat(format: SampleFormat, codec: String) { println("format: $format codec=$codec"); }
            override fun onSettings(settings: ServerSettings) = println("settings: $settings")
            override fun onServerError(message: String) = println("server error: $message")
        },
        clock,
    )
    engine.start(host, port)

    val blockFrames = 480
    val deadline = if (seconds > 0) clock.nowUs() + seconds * 1_000_000L else Long.MAX_VALUE
    var line: SourceDataLine? = null
    var written = 0L
    var lastReport = clock.nowUs()
    var buf = ShortArray(0)
    var bytes = ByteArray(0)
    var nextBlockUs = clock.nowUs()
    var wavStarted = false

    while (clock.nowUs() < deadline) {
        val b = engine.buffer
        val f = b?.format
        if (f == null) { Thread.sleep(20); continue }
        if (f != format) {
            format = f
            line?.close()
            written = 0
            if (play) {
                line = AudioSystem.getSourceDataLine(AudioFormat(f.rate.toFloat(), 16, f.channels, true, false)).apply {
                    open(this.format, f.rate / 10 * f.channels * 2) // ~100 ms
                    start()
                }
            }
            wav?.begin(f)
        }
        val n = blockFrames * f.channels
        if (buf.size < n) { buf = ShortArray(n); bytes = ByteArray(n * 2) }

        val dacUs: Long
        val l = line
        if (l != null) {
            val queuedFrames = written - l.longFramePosition
            dacUs = clock.nowUs() + queuedFrames * 1_000_000L / f.rate
        } else {
            // Simulated device: blocks are consumed in real time, 100 ms after we "write" them.
            val now = clock.nowUs()
            if (nextBlockUs > now) Thread.sleep((nextBlockUs - now) / 1000, ((nextBlockUs - now) % 1000 * 1000).toInt())
            dacUs = nextBlockUs + 100_000L
            nextBlockUs += blockFrames * 1_000_000L / f.rate
        }

        val played = engine.render(buf, blockFrames, dacUs)
        if (played && wav != null) {
            if (!wavStarted) { wavStarted = true; println("wavStartDacUs=$dacUs") }
            wav.write(buf, n)
        }
        if (l != null) {
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(buf, 0, n)
            l.write(bytes, 0, n * 2)
        }
        written += blockFrames

        val now = clock.nowUs()
        if (now - lastReport >= 1_000_000L) {
            lastReport = now
            val s = engine.stats()
            val t = engine.timeSync
            println(
                "offset=${t.offsetUs / 1000}ms rtt=${t.lastRttUs}us samples=${t.sampleCount} " +
                    (s?.let { "playing=${it.playing} err=${it.lastErrorUs}us median=${it.medianErrorUs}us ppm=${it.correctionPpm} queued=${it.queuedMs}ms hard=${it.hardSyncs} underruns=${it.underruns}" } ?: "")
            )
        }
    }
    engine.stop()
    line?.close()
    wav?.close()
}

private class WavWriter(private val file: File) {
    private var raf: RandomAccessFile? = null
    private var dataBytes = 0L
    private var format: SampleFormat? = null

    fun begin(f: SampleFormat) {
        if (raf != null) return // keep the first format only
        format = f
        raf = RandomAccessFile(file, "rw").apply { setLength(0); write(ByteArray(44)) }
    }

    fun write(samples: ShortArray, n: Int) {
        val r = raf ?: return
        val b = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) b.putShort(samples[i])
        r.write(b.array())
        dataBytes += n * 2
    }

    fun close() {
        val r = raf ?: return
        val f = format!!
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt((36 + dataBytes).toInt()).put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(f.channels.toShort()).putInt(f.rate)
            .putInt(f.rate * f.channels * 2).putShort((f.channels * 2).toShort()).putShort(16)
        h.put("data".toByteArray()).putInt(dataBytes.toInt())
        r.seek(0)
        r.write(h.array())
        r.close()
    }
}
