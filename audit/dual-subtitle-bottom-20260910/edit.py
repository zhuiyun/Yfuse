from pathlib import Path
p=Path('composeApp/src/androidMain/kotlin/com/yfuse/feature/player/Core2Surface.kt')
s=p.read_text()
s=s.replace('import androidx.compose.foundation.layout.fillMaxWidth','import androidx.compose.foundation.layout.height\nimport androidx.compose.foundation.layout.fillMaxWidth').replace('import androidx.compose.ui.unit.IntSize','import androidx.compose.ui.unit.DpSize\nimport androidx.compose.ui.unit.IntSize')
a=s.index('    Core2SubtitleChannel(\n',s.index('private fun Core2SubtitleOverlay'))
b=s.index('\n}\n\n@Composable\nprivate fun Core2SubtitleChannel',a)
s=s[:a]+'''    BoxWithConstraints(modifier) {
        val viewport = DpSize(maxWidth, maxHeight)
        val dual = playerState.secondarySubtitleTrackId != null
        val channel: @Composable (Boolean) -> Unit = { secondary ->
            Core2SubtitleChannel(
                cues = if (secondary) playerState.secondarySubtitleCues else playerState.subtitleCues,
                positionMs = subtitlePositionMs,
                timelineGeneration = playerState.diagnostics.outputEvidenceGeneration,
                canvasSize = canvasSize,
                offsetMs = if (secondary) playerState.secondarySubtitleOffsetMs else offsetMs,
                scale = scale,
                brightness = brightness,
                position = position,
                appearance = appearance,
                secondary = secondary,
                dual = dual,
                viewport = viewport,
                modifier = if (dual) Modifier.fillMaxWidth() else Modifier.fillMaxSize(),
            )
        }
        if (dual) {
            // Measure both tracks in one stack: multiline text and bitmap display sets reserve
            // their real height before the other channel is placed. The primary stays lowest.
            Column(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .padding(bottom = maxHeight * (1f - position.coerceIn(0.60f, 0.96f))),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                channel(true)
                channel(false)
            }
        } else {
            channel(false)
        }
    }'''+s[b:]
s=s.replace('    dual: Boolean,\n    modifier: Modifier,','    dual: Boolean,\n    viewport: DpSize,\n    modifier: Modifier,')
a=s.index('    BoxWithConstraints(modifier) {',s.index('private fun Core2SubtitleChannel'))
b=s.index('\ninternal fun core2SubtitleAlignment',a)
old=s[a:b]
textstart=old.index('                    payloads.forEach { payload ->')
textend=old.index('\n                }\n            }',textstart)
textbody=old[textstart:textend]
# Move the existing styled text rendering into a reusable composable.
textbody='\n'.join(line[20:] if line.startswith(' '*20) else line for line in textbody.splitlines())
new='''    val bitmapScale = scale.coerceIn(0.6f, 1.8f)
    val bitmapBounds = core2SubtitleBitmapBounds(activeBitmaps, bitmapScale)
    val bitmapContent: @Composable () -> Unit = {
        activeBitmaps.forEach { payload ->
            val bitmap = remember(payload) {
                Bitmap.createBitmap(payload.pixels, payload.width, payload.height, Bitmap.Config.ARGB_8888)
                    .asImageBitmap()
            }
            val scaledWidth = payload.width * bitmapScale
            val scaledHeight = payload.height * bitmapScale
            val x = payload.x - (scaledWidth - payload.width) / 2f
            val authoredY = (payload.y - (scaledHeight - payload.height) / 2f) / payload.canvasHeight
            // Translate the entire scaled display set together; ASS/PGS rectangles keep their
            // relative positions, including outlines and independently rendered glyphs.
            val y = if (dual) authoredY - bitmapBounds.first else
                authoredY + position.coerceIn(0.60f, 0.96f) - DEFAULT_SUBTITLE_POSITION
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.offset(
                    x = viewport.width * (x / payload.canvasWidth),
                    y = viewport.height * y,
                ).requiredSize(
                    width = viewport.width * (scaledWidth / payload.canvasWidth),
                    height = viewport.height * (scaledHeight / payload.canvasHeight),
                ).background(Color(appearance.backgroundColorArgb.toULong()))
                    .graphicsLayer(alpha = brightness.coerceIn(0.35f, 1f)),
            )
        }
    }
    if (dual) {
        Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
            if (activeBitmaps.isNotEmpty()) {
                Box(Modifier.fillMaxWidth().height(viewport.height * (bitmapBounds.second - bitmapBounds.first))) {
                    bitmapContent()
                }
            }
            if (activeText.isNotEmpty()) {
                Core2SubtitleText(activeText, 2, scale, brightness, appearance,
                    Modifier.fillMaxWidth(0.92f).padding(horizontal = 12.dp))
            }
        }
    } else {
        Box(modifier) {
            bitmapContent()
            activeText.groupBy { core2SubtitleAlignment(it.style.alignment, secondary, dual) }
                .forEach { (alignmentCode, payloads) ->
                    Core2SubtitleText(
                        payloads, alignmentCode, scale, brightness, appearance,
                        Modifier.align(alignmentCode.toComposeAlignment()).fillMaxWidth(0.92f)
                            .padding(horizontal = 12.dp).then(
                                when {
                                    alignmentCode <= 3 -> Modifier.padding(
                                        bottom = viewport.height * (1f - position.coerceIn(0.60f, 0.96f)),
                                    )
                                    alignmentCode >= 7 -> Modifier.padding(top = viewport.height * 0.05f)
                                    else -> Modifier
                                },
                            ),
                    )
                }
        }
    }
}

/** Normalized bounds after scaling, shared by measurement and placement. */
internal fun core2SubtitleBitmapBounds(
    bitmaps: List<YSubtitlePayload.BitmapArgb>,
    scale: Float,
): Pair<Float, Float> {
    if (bitmaps.isEmpty()) return 0f to 0f
    val normalizedScale = scale.coerceIn(0.6f, 1.8f)
    val top = bitmaps.minOf { (it.y - it.height * (normalizedScale - 1f) / 2f) / it.canvasHeight }
    val bottom = bitmaps.maxOf { (it.y + it.height * (normalizedScale + 1f) / 2f) / it.canvasHeight }
    return top to bottom
}

@Composable
private fun Core2SubtitleText(
    payloads: List<YSubtitlePayload.Text>,
    alignmentCode: Int,
    scale: Float,
    brightness: Float,
    appearance: SubtitleAppearance,
    modifier: Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
'''+ '\n'.join('        '+line for line in textbody.splitlines())+'''
    }
}
'''
s=s[:a]+new+s[b:]
s=s.replace('secondary -> 8','secondary -> 2')
p.write_text(s)
