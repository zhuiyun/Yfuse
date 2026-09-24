package com.yfuse.core.designsystem

import android.graphics.Point
import android.graphics.RuntimeShader
import android.os.Build
import android.view.Surface
import android.view.View
import android.view.WindowManager
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.platform.LocalView
import android.graphics.RenderEffect as AndroidRenderEffect

@Composable
internal actual fun rememberScreenGeometrySource(): ScreenGeometrySource {
    val view = LocalView.current
    return remember(view) { ScreenGeometrySource { screenGeometryOf(view) } }
}

/**
 * Rotation from the view's display, size from the largest window this app can have in that
 * rotation (the whole display for a full-screen app), and the window's own offset on the glass —
 * the player window is letterboxed away from a camera cutout in landscape, the page is not.
 */
internal fun screenGeometryOf(view: View): ScreenGeometry {
    val display = view.display
    val rotation = display?.rotation ?: Surface.ROTATION_0
    val size =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds =
                view.context
                    .getSystemService(WindowManager::class.java)
                    ?.maximumWindowMetrics
                    ?.bounds
            if (bounds != null) Size(bounds.width().toFloat(), bounds.height().toFloat()) else Size.Zero
        } else {
            val point = Point()
            @Suppress("DEPRECATION")
            display?.getRealSize(point)
            Size(point.x.toFloat(), point.y.toFloat())
        }
    val location = IntArray(2)
    view.getLocationOnScreen(location)
    return ScreenGeometry(rotation, size, Offset(location[0].toFloat(), location[1].toFloat()))
}

internal actual val supportsTideShader: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

/**
 * One damped crest per pixel, keyed on its distance from the origin: the library's cold-start
 * wave, turned radial. Leaving, a pixel is washed out a beat after its crest; surfacing, it is
 * transparent until the receding front reaches it and then rises from below into place.
 */
private const val TIDE_SHADER = """
uniform shader content;
uniform float2 origin;
uniform float time;
uniform float speed;
uniform float amplitude;
uniform float surfacing;

half4 main(float2 position) {
    float tau = time - distance(position, origin) / speed;
    if (surfacing < 0.5) {
        if (tau <= 0.0) {
            return content.eval(position);
        }
        float crest = -sin(6.2831853 * tau / 420.0) * exp(-tau / 190.0);
        float washed = clamp((tau - 160.0) / 240.0, 0.0, 1.0);
        return content.eval(float2(position.x, position.y - crest * amplitude)) * (1.0 - washed);
    }
    float rise = tau + 60.0;
    if (rise <= 0.0) {
        return half4(0.0);
    }
    float settle = exp(-rise / 190.0) * cos(6.2831853 * rise / 420.0);
    float shown = clamp(rise / 300.0, 0.0, 1.0);
    return content.eval(float2(position.x, position.y - settle * amplitude * 0.9)) * shown;
}
"""

private var tideShader: Any? = null

internal actual fun tideRenderEffect(
    size: Size,
    origin: Offset,
    timeMs: Float,
    speed: Float,
    amplitude: Float,
    surfacing: Boolean,
): RenderEffect? {
    if (!supportsTideShader || size.width <= 0f || size.height <= 0f) return null
    return buildTideEffect(origin, timeMs, speed, amplitude, surfacing)
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun buildTideEffect(
    origin: Offset,
    timeMs: Float,
    speed: Float,
    amplitude: Float,
    surfacing: Boolean,
): RenderEffect {
    // Compiling AGSL is the expensive part; the uniforms are cheap to rewrite every frame.
    val shader = (tideShader as? RuntimeShader) ?: RuntimeShader(TIDE_SHADER).also { tideShader = it }
    shader.setFloatUniform("origin", origin.x, origin.y)
    shader.setFloatUniform("time", timeMs)
    shader.setFloatUniform("speed", speed.coerceAtLeast(0.01f))
    shader.setFloatUniform("amplitude", amplitude)
    shader.setFloatUniform("surfacing", if (surfacing) 1f else 0f)
    return AndroidRenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect()
}
