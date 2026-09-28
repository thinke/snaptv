package io.github.thinke.snaptv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

/**
 * GPU styles: the whole picture is one fragment shader, run for every pixel on the GPU. The CPU
 * only hands it a few numbers per frame ([ShaderInputs]), so effects that would be far too
 * heavy as Canvas drawing (glow everywhere, full-screen patterns) cost almost nothing.
 *
 * Each platform runs the shared effect code its own way (Skia runtime shaders on the desktop,
 * an OpenGL ES view on Android, whose Compose can't run shaders before Android 13) and provides
 * it through [LocalShaderEffects]. Where none is provided, GPU styles aren't offered
 * ([visualStyleOf]).
 */
interface ShaderEffects {
    /**
     * Shows [style], one of the [VisualStyle.gpu] styles, filling [modifier]'s bounds. [inputs]
     * are updated every frame, and [frame] changes with them.
     */
    @Composable
    fun Effect(style: VisualStyle, inputs: ShaderInputs, frame: State<Long>, modifier: Modifier)
}

val LocalShaderEffects = staticCompositionLocalOf<ShaderEffects?> { null }

/** The style for a saved index, among those this screen can draw (GPU styles only with [LocalShaderEffects]). */
@Composable
fun visualStyleOf(index: Int): VisualStyle = VisualStyle.of(index, gpu = LocalShaderEffects.current != null)

/** What a shader gets each frame. */
class ShaderInputs {
    /** Spectrum in [BANDS] bands, 0..1, lows first. */
    val bands = FloatArray(BANDS)
    var time = 0f
    /**
     * Distance flown down the tunnel (faster when the music is loud), as each pattern's phase,
     * 0..1: the rings repeat every 1/3, the ribs' twist every 20/3, the colour every 20.
     */
    var ringPhase = 0f
    var ribPhase = 0f
    var huePhase = 0f
    var bass = 0f
    var loudness = 0f
    /** 1 on a beat, falling towards 0 after it. */
    var beat = 0f
    /** Base hue 0..1, drifting slowly like the Canvas styles' palette. */
    var hue = 0f
    /**
     * Per-frame values the same for every pixel, worked out once here rather than per pixel
     * on the GPU (it matters on TV GPUs): the tunnel's sway, and the beat flash's colour.
     */
    var swayX = 0f
    var swayY = 0f
    val flash = FloatArray(3)

    companion object {
        const val BANDS = 24
    }
}

/**
 * The shader effects, in GLSL-style code that both Skia's SkSL and OpenGL ES 2 accept (vec types,
 * no platform built-ins). Each platform adds [UNIFORMS], a `band()` and its own main().
 */
object ShaderSource {
    /** Uniforms every effect reads; the platform wrapper adds these and a band() (see [BAND_LOOP]). */
    const val UNIFORMS = """
uniform vec2 resolution;
uniform float time;
uniform float ringPhase;
uniform float ribPhase;
uniform float huePhase;
uniform float bass;
uniform float loudness;
uniform float beat;
uniform float hue;
uniform vec2 sway;
uniform vec3 flash;
"""

    /**
     * `float band(float x)`: the spectrum level at x (0..1, lows first), interpolated. Where
     * uniform arrays can't be indexed by a computed value (Skia), a loop over all bands; OpenGL
     * ES uses a 24×1 texture instead (one lookup per pixel), see its wrapper.
     */
    const val BAND_LOOP = """
uniform float bands[24];
float band(float x) {
    float f = clamp(x, 0.0, 1.0) * 23.0;
    float level = 0.0;
    for (int k = 0; k < 24; k++) level += bands[k] * max(0.0, 1.0 - abs(f - float(k)));
    return level;
}
"""

    /**
     * Flying down a tunnel of glowing rings and ribs. The walls around you light up with the
     * spectrum (lows at the top and bottom, highs at the sides), the rings thicken with the
     * bass, and each beat flashes at the far end. `vec3 tunnel(vec2 p)`, p in pixels.
     */
    const val TUNNEL = """
vec3 hsv(float h, float s, float v) {
    vec3 k = clamp(abs(fract(h + vec3(0.0, 2.0, 1.0) / 3.0) * 6.0 - 3.0) - 1.0, 0.0, 1.0);
    return v * mix(vec3(1.0), k, s);
}

vec3 tunnel(vec2 p) {
    vec2 uv = (p - 0.5 * resolution) / resolution.y;
    // The tunnel sways slowly, so it feels like flying rather than a static pipe.
    uv -= sway;
    float r = max(length(uv), 0.001);
    float a = atan(uv.y, uv.x);
    float around = a / 6.2831853 + 0.5;
    // How far down the tunnel this pixel looks. The distance flown comes in as each pattern's
    // phase (0..1, from the CPU), so the numbers here stay small: exact enough for 16-bit maths
    // on TV GPUs, where a large distance would band the rings.
    float near = 0.32 / r;

    // Mirror the spectrum around the walls: lows at the top and bottom, highs at the sides.
    float mirrored = abs(fract(around * 2.0 + 0.25) * 2.0 - 1.0);
    float level = band(mirrored);

    // Cheap on weak GPUs: no pow() (a log and an exp per pixel). The ring's sharpness is a
    // smoothstep whose width grows with the bass; the rib is x^24 by multiplication.
    float ring = 0.5 + 0.5 * cos(6.2831853 * (near * 3.0 + ringPhase));
    ring = smoothstep(0.82 - 0.4 * bass, 1.0, ring);
    float x = 0.5 + 0.5 * cos(6.2831853 * (around * 16.0 + near * 0.15 + ribPhase));
    float x2 = x * x;
    float x4 = x2 * x2;
    float x8 = x4 * x4;
    float rib = x8 * x8 * x8;
    float wall = max(ring, 0.55 * rib);

    // Near the centre is far away: fade into fog so the rings don't shimmer there.
    float fog = smoothstep(0.02, 0.5, r);
    vec3 color = hsv(hue + 0.25 * mirrored + 0.05 * near + huePhase, 0.75, 1.0);
    vec3 c = color * wall * (0.25 + 1.6 * level) * fog;
    // A soft glow over the walls, brighter when it's loud.
    c += color * (0.05 + 0.2 * loudness) * fog * (0.4 + level);
    // Each beat flashes at the far end.
    c += flash * smoothstep(0.25, 0.0, r);
    return c;
}
"""
}
