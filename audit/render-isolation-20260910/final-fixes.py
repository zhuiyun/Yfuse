from edit import read, write

C='composeApp/src/commonMain/kotlin/com/yfuse/'
A='composeApp/src/androidMain/kotlin/com/yfuse/'
T='composeApp/src/commonTest/kotlin/com/yfuse/'

p=C+'feature/player/PlayerControls.kt'
s=read(p)
s=s.replace('''                    RefinedTopBar(
                        title''','''                    val readout by remember(playback, sourceLabel, containerLabel) {
                        derivedStateOf { playback.value.readoutLine(sourceLabel, containerLabel) }
                    }
                    RefinedTopBar(
                        title''',1)
s=s.replace('subtitle = state.readoutLine(sourceLabel, containerLabel),', 'subtitle = readout,',1)
write(p,s)

p=A+'feature/player/PlayerRoot.kt'
write(p,read(p).replace('        val latestLocalState by liveLocalState\n',''))

p=T+'core/designsystem/ThemeCrossfadeTest.kt'
s=read(p).replace('    fun apply() = advance(16)', '''    fun apply() {
        // Apply changes and start the effect's clock before advancing elapsed animation time.
        repeat(3) { advance(0) }
    }''')
write(p,s)

p=C+'core/designsystem/Glass.kt'
s=read(p)
old='''        onDrawBehind {
            val color = animatedFill.value
            val surface =
                when {
                    accessibility.reduceTransparency -> {
                        val opaque = reducedTransparencyFill(color, palette)
                        Brush.linearGradient(listOf(opaque, opaque))
                    }
                    frosted -> frostedSurfaceBrush(color, palette, weight.frostDensity)
                    else -> liquidSurfaceBrush(color, palette, weight)
                }
            drawOutline(outline, brush = surface)'''
new='''        // The cache observes paint state, so steady surfaces reuse their brush between draws.
        val color = animatedFill.value
        val surface =
            when {
                accessibility.reduceTransparency -> {
                    val opaque = reducedTransparencyFill(color, palette)
                    Brush.linearGradient(listOf(opaque, opaque))
                }
                frosted -> frostedSurfaceBrush(color, palette, weight.frostDensity)
                else -> liquidSurfaceBrush(color, palette, weight)
            }
        onDrawBehind {
            drawOutline(outline, brush = surface)'''
assert old in s
write(p,s.replace(old,new,1))

p=C+'feature/player/PlayerBatteryStatus.kt'
s=read(p).replace('import androidx.compose.ui.graphics.Color', 'import androidx.compose.ui.graphics.Color\nimport androidx.compose.ui.graphics.Path')
s=s.replace('''        ThemeText(
            "${current.percent}%${if (current.charging) " ⚡" else ""}",''','''        if (current.charging) {
            Canvas(Modifier.size(7.dp, 11.dp)) {
                val bolt = Path().apply {
                    moveTo(size.width * 0.65f, 0f)
                    lineTo(0f, size.height * 0.58f)
                    lineTo(size.width * 0.42f, size.height * 0.58f)
                    lineTo(size.width * 0.3f, size.height)
                    lineTo(size.width, size.height * 0.38f)
                    lineTo(size.width * 0.57f, size.height * 0.38f)
                    close()
                }
                drawPath(bolt, color)
            }
        }
        ThemeText(
            "${current.percent}%",''')
write(p,s)

p=A+'feature/player/SubtitleBitmapCanvas.android.kt'
s=read(p).replace('import androidx.compose.ui.unit.IntOffset', 'import androidx.compose.ui.unit.IntOffset\nimport androidx.compose.ui.unit.IntRect')
start=s.index('                val width = payload.width * bitmapScale')
end=s.index('                if (background.alpha > 0f)',start)
s=s[:start]+'''                val destination = subtitleBitmapDestination(
                    payload,
                    bitmapScale,
                    IntSize(viewport.width.roundToPx(), viewport.height.roundToPx()),
                    if (dual) -bounds.value.first else position.coerceIn(0.60f, 0.96f) - DEFAULT_SUBTITLE_POSITION,
                )
'''+s[end:]
s=s.replace('Offset(left, top)', 'Offset(destination.left.toFloat(), destination.top.toFloat())')
s=s.replace('Size(imageSize.width.toFloat(), imageSize.height.toFloat())','Size(destination.width.toFloat(), destination.height.toFloat())')
s=s.replace('dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),\n                    dstSize = imageSize,', 'dstOffset = IntOffset(destination.left, destination.top),\n                    dstSize = IntSize(destination.width, destination.height),')
s+='''
/** Authored coordinates retain their centre while scale and whole-track placement change. */
internal fun subtitleBitmapDestination(
    payload: YSubtitlePayload.BitmapArgb,
    scale: Float,
    viewport: IntSize,
    verticalShift: Float,
): IntRect {
    val boundedScale = scale.coerceIn(0.6f, 1.8f)
    val width = payload.width * boundedScale
    val height = payload.height * boundedScale
    val x = (payload.x - (width - payload.width) / 2f) / payload.canvasWidth
    val y = (payload.y - (height - payload.height) / 2f) / payload.canvasHeight + verticalShift
    val left = (viewport.width * x).roundToInt()
    val top = (viewport.height * y).roundToInt()
    return IntRect(
        left,
        top,
        left + (viewport.width * width / payload.canvasWidth).roundToInt().coerceAtLeast(1),
        top + (viewport.height * height / payload.canvasHeight).roundToInt().coerceAtLeast(1),
    )
}
'''
write(p,s)
write('composeApp/src/androidUnitTest/kotlin/com/yfuse/feature/player/SubtitleBitmapCanvasTest.kt','''package com.yfuse.feature.player

import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import com.yfuse.core2.subtitle.YSubtitlePayload
import kotlin.test.Test
import kotlin.test.assertEquals

class SubtitleBitmapCanvasTest {
    private val bitmap = YSubtitlePayload.BitmapArgb(
        width = 200, height = 100, x = 100, y = 700,
        canvasWidth = 1000, canvasHeight = 1000, pixels = IntArray(20_000),
    )

    @Test
    fun viewport_resize_and_bitmap_scaling_keep_the_authored_centre() {
        assertEquals(IntRect(100, 700, 300, 800), subtitleBitmapDestination(bitmap, 1f, IntSize(1000, 1000), 0f))
        assertEquals(IntRect(40, 660, 760, 840), subtitleBitmapDestination(bitmap, 1.8f, IntSize(2000, 1000), 0f))
    }

    @Test
    fun dual_track_normalizes_its_display_set_and_global_position_moves_the_whole_set() {
        val bounds = core2SubtitleBitmapBounds(listOf(bitmap), 1.8f)
        assertEquals(IntRect(20, 0, 380, 180), subtitleBitmapDestination(bitmap, 1.8f, IntSize(1000, 1000), -bounds.first))
        assertEquals(IntRect(100, 600, 300, 700), subtitleBitmapDestination(bitmap, 1f, IntSize(1000, 1000), -0.1f))
    }

    @Test
    fun pixel_replacement_has_no_effect_on_measurement_or_placement() {
        val recolored = bitmap.copy(pixels = IntArray(20_000) { -1 })
        assertEquals(core2SubtitleBitmapBounds(listOf(bitmap), 1f), core2SubtitleBitmapBounds(listOf(recolored), 1f))
        assertEquals(
            subtitleBitmapDestination(bitmap, 1f, IntSize(1920, 1080), 0f),
            subtitleBitmapDestination(recolored, 1f, IntSize(1920, 1080), 0f),
        )
    }
}
''')
