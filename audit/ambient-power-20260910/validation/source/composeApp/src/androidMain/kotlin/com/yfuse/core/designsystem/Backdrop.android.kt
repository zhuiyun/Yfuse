package com.yfuse.core.designsystem

import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect
import android.graphics.RenderEffect as AndroidRenderEffect

/**
 * `RenderEffect` — and so Compose's `GraphicsLayer.renderEffect` — arrived in API 31.
 *
 * Below it the property is silently ignored, which would leave the capture paying for a
 * full-screen layer every frame and drawing an unblurred copy of the page under the bar.
 * This app's `minSdk` is 26, so that range is real and has to opt out rather than degrade.
 */
actual val supportsBackdropBlur: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * The lens. Inside the outer `edge` fraction of each axis the sample point moves inward,
 * linearly from `strength` at the rim to nothing at the inner boundary, so the backdrop
 * bends as it passes under the edge of the glass and stays put underneath the glyphs.
 * Every displacement points inward, so the shader never reads outside the captured
 * content.
 */
private const val REFRACTION_SHADER = """
uniform shader content;
uniform float2 size;
uniform float2 edge;
uniform float strength;

half4 main(float2 position) {
    float2 uv = position / size;
    float2 shift = float2(0.0, 0.0);
    if (uv.x < edge.x) {
        shift.x = 1.0 - uv.x / edge.x;
    } else if (uv.x > 1.0 - edge.x) {
        shift.x = -(1.0 - (1.0 - uv.x) / edge.x);
    }
    if (uv.y < edge.y) {
        shift.y = 1.0 - uv.y / edge.y;
    } else if (uv.y > 1.0 - edge.y) {
        shift.y = -(1.0 - (1.0 - uv.y) / edge.y);
    }
    return content.eval(position + shift * strength);
}
"""

/**
 * `RuntimeShader` is API 33. On 31 and 32 the surface still blurs and keeps every layer
 * drawn on top of the blur; only the bend at the rim is missing.
 */
actual fun refractiveBlurEffect(
    blurRadiusPx: Float,
    widthPx: Float,
    heightPx: Float,
    refraction: BackdropRefraction,
    strengthPx: Float,
): RenderEffect? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
    if (widthPx <= 0f || heightPx <= 0f) return null
    return buildRefractiveBlurEffect(blurRadiusPx, widthPx, heightPx, refraction, strengthPx)
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun buildRefractiveBlurEffect(
    blurRadiusPx: Float,
    widthPx: Float,
    heightPx: Float,
    refraction: BackdropRefraction,
    strengthPx: Float,
): RenderEffect {
    val shader =
        RuntimeShader(REFRACTION_SHADER).apply {
            setFloatUniform("size", widthPx, heightPx)
            setFloatUniform("edge", refraction.edgeX, refraction.edgeY)
            setFloatUniform("strength", strengthPx)
        }
    val refract = AndroidRenderEffect.createRuntimeShaderEffect(shader, "content")
    if (blurRadiusPx <= 0f) return refract.asComposeRenderEffect()
    // Bend first, then blur: blurring first would soften the very edge the bend is there
    // to make visible.
    val blur = AndroidRenderEffect.createBlurEffect(blurRadiusPx, blurRadiusPx, Shader.TileMode.CLAMP)
    return AndroidRenderEffect.createChainEffect(blur, refract).asComposeRenderEffect()
}
