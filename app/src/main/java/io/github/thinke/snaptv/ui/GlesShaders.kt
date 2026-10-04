package io.github.thinke.snaptv.ui

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * GPU styles on Android: the shared effects ([ShaderSource]) in an OpenGL ES 2 view. Compose on
 * Android only runs shaders from Android 13 (AGSL), and TVs are often older, so the effect
 * draws on its own GL surface instead, behind the Compose overlays. The GL thread draws every
 * vsync and reads [ShaderInputs] as the visualizer updates them.
 */
class GlesShaderEffects(
    /** Log the frame rate every few seconds (the bench build); gfxinfo doesn't see GL surfaces. */
    private val logFps: Boolean = false,
) : ShaderEffects {
    @Composable
    override fun Effect(style: VisualStyle, inputs: ShaderInputs, frame: State<Long>, modifier: Modifier) {
        AndroidView(
            factory = { ShaderView(it, inputs, style, logFps) },
            modifier = modifier,
            update = { it.style = style },
        )
    }
}

private class ShaderView(context: Context, inputs: ShaderInputs, style: VisualStyle, logFps: Boolean) : GLSurfaceView(context) {
    private val renderer = ShaderRenderer(inputs, logFps)

    var style: VisualStyle = style
        set(value) {
            val resize = value != field
            field = value
            renderer.style = value
            if (resize) fitSurface()
        }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fitSurface()
    }

    /**
     * Soft glow is drawn at 2/3 size and scaled up by the display: it looks the same, and TV
     * GPUs (a PowerVR BXE-4-32 on the TCL) get 2.25x fewer pixels. Spectrum's bars have sharp
     * edges that scaling would blur, and are cheap enough to draw at full size.
     */
    private fun fitSurface() {
        if (width <= 0 || height <= 0) return
        if (style == VisualStyle.Bars) holder.setFixedSize(width, height)
        else holder.setFixedSize(width * 2 / 3, height * 2 / 3)
    }

    init {
        renderer.style = style
        setEGLContextClientVersion(2)
        // 8 bits a channel: the default may be 16-bit colour, which bands the soft glow.
        setEGLConfigChooser(8, 8, 8, 8, 0, 0)
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }
}

private class ShaderRenderer(private val inputs: ShaderInputs, private val logFps: Boolean) : GLSurfaceView.Renderer {
    private var frames = 0
    private var since = 0L
    @Volatile var style: VisualStyle = VisualStyle.Tunnel
    private val programs = HashMap<VisualStyle, Program?>()
    private var width = 1
    private var height = 1
    private val triangle = ByteBuffer.allocateDirect(6 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        // One triangle covering the screen (clip space), cheaper than two.
        put(floatArrayOf(-1f, -1f, 3f, -1f, -1f, 3f)).position(0)
    }
    private val bands = FloatArray(ShaderInputs.BANDS)
    private val bandBytes = ByteBuffer.allocateDirect(ShaderInputs.BANDS)
    private var bandsTexture = 0
    private val bars = FloatArray(ShaderInputs.BARS)
    private val peaks = FloatArray(ShaderInputs.BARS)
    private val barBytes = ByteBuffer.allocateDirect(ShaderInputs.BARS * 4)
    private var barsTexture = 0

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // Bench: no vsync, so the logged rate is what the GPU manages, not a divisor of 60 Hz.
        if (logFps) android.opengl.EGL14.eglSwapInterval(android.opengl.EGL14.eglGetCurrentDisplay(), 0)
        programs.clear() // a new EGL context: old programs and textures are gone with it
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        bandsTexture = texture(GLES20.GL_LINEAR)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, ShaderInputs.BANDS, 1, 0, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, bandBytes)
        // One texel a bar, read exactly: no filtering between neighbours.
        barsTexture = texture(GLES20.GL_NEAREST)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, ShaderInputs.BARS, 1, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, barBytes)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun texture(filter: Int): Int {
        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, filter)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, filter)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return t[0]
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = w
        height = h
        GLES20.glViewport(0, 0, w, h)
    }

    override fun onDrawFrame(gl: GL10?) {
        val p = programs.getOrPut(style) { Program.build(style) }
        if (p == null) {
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            return
        }
        GLES20.glUseProgram(p.id)
        inputs.bands.copyInto(bands)
        GLES20.glUniform2f(p.resolution, width.toFloat(), height.toFloat())
        GLES20.glUniform1f(p.time, inputs.time)
        GLES20.glUniform1f(p.ringPhase, inputs.ringPhase)
        GLES20.glUniform1f(p.ribPhase, inputs.ribPhase)
        GLES20.glUniform1f(p.huePhase, inputs.huePhase)
        GLES20.glUniform1f(p.bass, inputs.bass)
        GLES20.glUniform1f(p.loudness, inputs.loudness)
        GLES20.glUniform1f(p.beat, inputs.beat)
        GLES20.glUniform1f(p.hue, inputs.hue)
        GLES20.glUniform2f(p.sway, inputs.swayX, inputs.swayY)
        GLES20.glUniform3f(p.flash, inputs.flash[0], inputs.flash[1], inputs.flash[2])
        GLES20.glUniform3f(p.glow, inputs.glow[0], inputs.glow[1], inputs.glow[2])
        uploadBands()
        GLES20.glUniform1i(p.bandsTexture, 0)
        if (style == VisualStyle.Bars) {
            inputs.bars.copyInto(bars)
            inputs.peaks.copyInto(peaks)
            uploadBars()
            GLES20.glUniform1i(p.barsTexture, 1)
        }
        GLES20.glEnableVertexAttribArray(p.position)
        GLES20.glVertexAttribPointer(p.position, 2, GLES20.GL_FLOAT, false, 0, triangle)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3)
        if (logFps) countFrame()
    }

    /** The spectrum as a 24×1 texture, 8 bits a band: plenty for brightness. */
    private fun uploadBands() {
        bandBytes.clear()
        for (v in bands) bandBytes.put((v.coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte())
        bandBytes.position(0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, bandsTexture)
        GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, ShaderInputs.BANDS, 1, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, bandBytes)
    }

    /**
     * Bar i as texel i: its level in red and green, its peak in blue and alpha, each 16 bits
     * (high byte, low byte), since 8 bits would step the bars visibly on a tall screen.
     */
    private fun uploadBars() {
        barBytes.clear()
        for (i in 0 until ShaderInputs.BARS) {
            put16(bars[i])
            put16(peaks[i])
        }
        barBytes.position(0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, barsTexture)
        GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, ShaderInputs.BARS, 1, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, barBytes)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    }

    private fun put16(v: Float) {
        val scaled = v.coerceIn(0f, 1f) * 255f
        val high = scaled.toInt()
        barBytes.put(high.toByte())
        barBytes.put(((scaled - high) * 255f + 0.5f).toInt().coerceAtMost(255).toByte())
    }

    private fun countFrame() {
        val now = System.nanoTime()
        if (since == 0L) since = now
        frames++
        if (now - since >= 5_000_000_000L) {
            Log.i("SnapTV.GL", "$style ${"%.1f".format(frames * 1e9 / (now - since))} fps at ${width}x$height")
            frames = 0
            since = now
        }
    }
}

private class Program(val id: Int) {
    val position = GLES20.glGetAttribLocation(id, "position")
    val resolution = GLES20.glGetUniformLocation(id, "resolution")
    val time = GLES20.glGetUniformLocation(id, "time")
    val ringPhase = GLES20.glGetUniformLocation(id, "ringPhase")
    val ribPhase = GLES20.glGetUniformLocation(id, "ribPhase")
    val huePhase = GLES20.glGetUniformLocation(id, "huePhase")
    val bass = GLES20.glGetUniformLocation(id, "bass")
    val loudness = GLES20.glGetUniformLocation(id, "loudness")
    val beat = GLES20.glGetUniformLocation(id, "beat")
    val hue = GLES20.glGetUniformLocation(id, "hue")
    val sway = GLES20.glGetUniformLocation(id, "sway")
    val flash = GLES20.glGetUniformLocation(id, "flash")
    val glow = GLES20.glGetUniformLocation(id, "glow")
    val bandsTexture = GLES20.glGetUniformLocation(id, "bandsTexture")
    val barsTexture = GLES20.glGetUniformLocation(id, "barsTexture")

    companion object {
        private const val TAG = "SnapTV.GL"

        private const val VERTEX = """
attribute vec2 position;
void main() { gl_Position = vec4(position, 0.0, 1.0); }
"""

        /**
         * The shared effect with GL's main(): pixel coordinates with y down, as in Skia. 16-bit
         * maths unless [exact]: faster on TV GPUs, and enough where the effect keeps its numbers
         * small. Spectrum works in pixels, which 16 bits can't place exactly past 1024.
         */
        private fun fragment(effect: String, function: String, exact: Boolean = false) = """
${if (exact) "#ifdef GL_FRAGMENT_PRECISION_HIGH\nprecision highp float;\n#else\nprecision mediump float;\n#endif" else "precision mediump float;"}
${ShaderSource.UNIFORMS}
uniform sampler2D bandsTexture;
float band(float x) {
    // Texel k holds band k; linear filtering interpolates between neighbours.
    return texture2D(bandsTexture, vec2((clamp(x, 0.0, 1.0) * 23.0 + 0.5) / 24.0, 0.5)).r;
}
uniform sampler2D barsTexture;
float bar(float i) {
    vec4 t = texture2D(barsTexture, vec2((i + 0.5) / ${ShaderInputs.BARS}.0, 0.5));
    return t.r + t.g / 255.0;
}
float peak(float i) {
    vec4 t = texture2D(barsTexture, vec2((i + 0.5) / ${ShaderInputs.BARS}.0, 0.5));
    return t.b + t.a / 255.0;
}
$effect
void main() {
    vec2 p = vec2(gl_FragCoord.x, resolution.y - gl_FragCoord.y);
    gl_FragColor = vec4($function(p), 1.0);
}
"""

        fun build(style: VisualStyle): Program? {
            val source = when (style) {
                VisualStyle.Tunnel -> fragment(ShaderSource.TUNNEL, "tunnel")
                VisualStyle.Bars -> fragment(ShaderSource.BARS, "spectrumBars", exact = true)
                else -> return null
            }
            val vs = compile(GLES20.GL_VERTEX_SHADER, VERTEX) ?: return null
            val fs = compile(GLES20.GL_FRAGMENT_SHADER, source) ?: return null
            val id = GLES20.glCreateProgram()
            GLES20.glAttachShader(id, vs)
            GLES20.glAttachShader(id, fs)
            GLES20.glLinkProgram(id)
            val ok = IntArray(1)
            GLES20.glGetProgramiv(id, GLES20.GL_LINK_STATUS, ok, 0)
            if (ok[0] == 0) {
                Log.w(TAG, "link failed for $style: ${GLES20.glGetProgramInfoLog(id)}")
                return null
            }
            return Program(id)
        }

        private fun compile(type: Int, source: String): Int? {
            val id = GLES20.glCreateShader(type)
            GLES20.glShaderSource(id, source)
            GLES20.glCompileShader(id)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) {
                Log.w(TAG, "compile failed: ${GLES20.glGetShaderInfoLog(id)}")
                return null
            }
            return id
        }
    }
}
