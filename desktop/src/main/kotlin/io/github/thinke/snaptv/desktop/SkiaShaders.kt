package io.github.thinke.snaptv.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asComposeShader
import androidx.compose.ui.graphics.drawscope.DrawScope
import io.github.thinke.snaptv.ui.ShaderEffects
import io.github.thinke.snaptv.ui.ShaderInputs
import io.github.thinke.snaptv.ui.ShaderSource
import io.github.thinke.snaptv.ui.VisualStyle
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder

/**
 * GPU styles on the desktop: the shared effects as Skia runtime shaders (SkSL). Skia turns them
 * into GLSL for the OpenGL renderer Compose draws with, so they run on the GPU like the rest of
 * the window; the CPU only sets a few uniforms per frame.
 */
class SkiaShaderEffects : ShaderEffects {
    private val tunnel: RuntimeShaderBuilder by lazy {
        RuntimeShaderBuilder(RuntimeEffect.makeForShader(ShaderSource.UNIFORMS + ShaderSource.BAND_LOOP + ShaderSource.TUNNEL + MAIN.format("tunnel")))
    }

    @Composable
    override fun Effect(style: VisualStyle, inputs: ShaderInputs, frame: State<Long>, modifier: Modifier) {
        Canvas(modifier) {
            frame.value // redraw every frame
            draw(this, style, inputs)
        }
    }

    private fun draw(scope: DrawScope, style: VisualStyle, inputs: ShaderInputs) {
        val b = when (style) {
            VisualStyle.Tunnel -> tunnel
            else -> return
        }
        b.uniform("resolution", scope.size.width, scope.size.height)
        b.uniform("time", inputs.time)
        b.uniform("ringPhase", inputs.ringPhase)
        b.uniform("ribPhase", inputs.ribPhase)
        b.uniform("huePhase", inputs.huePhase)
        b.uniform("bass", inputs.bass)
        b.uniform("loudness", inputs.loudness)
        b.uniform("beat", inputs.beat)
        b.uniform("hue", inputs.hue)
        b.uniform("sway", inputs.swayX, inputs.swayY)
        b.uniform("flash", inputs.flash[0], inputs.flash[1], inputs.flash[2])
        b.uniform("bands", inputs.bands)
        scope.drawRect(ShaderBrush(b.makeShader().asComposeShader()), Offset.Zero, scope.size)
    }

    private companion object {
        const val MAIN = "\nhalf4 main(float2 p) { return half4(%s(p), 1.0); }\n"
    }
}
