package io.github.thinke.snaptv

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Every decoder option on this device (SnapTV, FFmpeg, each MediaCodec) through the self-test. */
@RunWith(AndroidJUnit4::class)
class MediaCodecDecodersTest {
    @Test
    fun everyDecoderPassesTheSelfTest() {
        val results = DecoderSelfTest.runAll(InstrumentationRegistry.getInstrumentation().targetContext)
        val report = results.joinToString("\n") { "${if (it.ok) "OK  " else "FAIL"} ${it.codec} ${it.option.label}: ${it.detail}" }
        android.util.Log.i("DecoderSelfTest", report)
        assertTrue("need FFmpeg and SnapTV options", results.any { it.option.id == DecoderCatalog.FFMPEG } && results.any { it.option.id == DecoderCatalog.KOTLIN })
        assertTrue(report, results.all { it.ok })
    }
}

/** Tries FFmpeg for codecs its build doesn't advertise, to see whether it really can't decode them. */
@RunWith(AndroidJUnit4::class)
class FfmpegProbeTest {
    @Test
    fun probeUnlistedCodecs() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        for (codec in listOf("opus", "ogg")) {
            val r = DecoderSelfTest.run(ctx, codec, DecoderOption(DecoderCatalog.FFMPEG, "FFmpeg (forced)"))
            android.util.Log.i("FfmpegProbe", "$codec: supports()=${FfmpegDecoder.supports(codec)} -> ${if (r.ok) "OK" else "FAIL"} ${r.detail}")
        }
    }
}
