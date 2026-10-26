package com.yfuse.feature.widgets

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.widget.WidgetMedia

/** Three artwork planes share the vertices of one rotating solid. */
@Composable
internal fun WidgetPrismGallery(
    items: List<WidgetMedia>,
    metadata: Boolean,
    compact: Boolean,
    onOpen: (WidgetMedia) -> Unit,
) {
    if (items.isEmpty()) return
    val density = LocalDensity.current
    val shadow = LocalPalette.current.text
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        val stageWidth = minOf(maxWidth, if (compact) 340.dp else 480.dp)
        val motion = rememberWidgetGalleryMotion("prism-gallery", items, with(density) { (stageWidth * .54f).toPx() })
        val faces = prismGalleryFaces(motion.position, items.size, motion.rtl)
        val currentFaces by rememberUpdatedState(faces)
        val currentMotion by rememberUpdatedState(motion)
        val currentItems by rememberUpdatedState(items)
        val open by rememberUpdatedState(onOpen)
        Column(Modifier.width(stageWidth), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(stageWidth * .91f)
                    .clipToBounds()
                    .pointerInput(Unit) {
                        detectTapGestures { point ->
                            val normalized =
                                GalleryPoint(
                                    point.x / size.width.coerceAtLeast(1),
                                    point.y / size.height.coerceAtLeast(1),
                                )
                            val face =
                                currentFaces.asReversed().firstOrNull {
                                    galleryQuadContains(
                                        it.corners,
                                        normalized,
                                    )
                                }
                            val item = face?.let { currentItems.getOrNull(it.index) }
                            if (face != null && item != null) {
                                if (face.index ==
                                    currentMotion.currentIndex
                                ) {
                                    open(item)
                                } else {
                                    currentMotion.select(face.index)
                                }
                            }
                        }
                    }.onKeyEvent { event ->
                        if (event.type == KeyEventType.KeyUp &&
                            (event.key == Key.Enter || event.key == Key.DirectionCenter)
                        ) {
                            currentItems.getOrNull(currentMotion.currentIndex)?.let(open)
                            true
                        } else {
                            false
                        }
                    }.semantics {
                        role = Role.Button
                        contentDescription = items[motion.currentIndex].title
                        onClick("查看详情") {
                            onOpen(items[motion.currentIndex])
                            true
                        }
                    }.then(motion.inputModifier),
            ) {
                Canvas(Modifier.fillMaxSize().clearAndSetSemantics { }) {
                    // A bounded contact shadow makes the shared planes read as a single object.
                    repeat(7) { layer ->
                        val expansion = layer * .012f
                        drawOval(
                            shadow.copy(alpha = .018f),
                            topLeft = Offset(size.width * (.18f - expansion), size.height * (.86f - expansion / 3)),
                            size = Size(size.width * (.64f + expansion * 2), size.height * (.06f + expansion * 2 / 3)),
                        )
                    }
                }
                faces.forEach { face ->
                    Artwork(
                        items[face.index],
                        Modifier.fillMaxSize().clearAndSetSemantics { }.drawWithContent {
                            // Orthographic cube faces are parallelograms; this affine map
                            // preserves their common edges throughout the rotation.
                            val matrix = prismGalleryMatrix(face, size, motion.rtl)
                            withTransform({ transform(matrix) }) {
                                this@drawWithContent.drawContent()
                                drawRect(Color.Black.copy(alpha = face.shade))
                                drawRect(
                                    Brush.verticalGradient(listOf(Color.White.copy(alpha = .12f), Color.Transparent)),
                                )
                            }
                        },
                        poster = true,
                    )
                }
                Canvas(Modifier.fillMaxSize().clearAndSetSemantics { }) {
                    faces.forEach { face ->
                        val outline =
                            Path().apply {
                                face.corners.forEachIndexed { index, point ->
                                    if (index ==
                                        0
                                    ) {
                                        moveTo(point.x * size.width, point.y * size.height)
                                    } else {
                                        lineTo(
                                            point.x * size.width,
                                            point.y * size.height,
                                        )
                                    }
                                }
                                close()
                            }
                        drawPath(outline, Color.White.copy(alpha = .22f), style = Stroke(.7.dp.toPx()))
                    }
                }
            }
            WidgetGalleryFooter(items, metadata, motion, onOpen)
        }
    }
}

/** Use Compose's actual matrix convention, including a texture correction for RTL. */
internal fun prismGalleryMatrix(
    face: PrismGalleryFace,
    size: Size,
    rtl: Boolean,
): Matrix {
    // Mirror the solid's arrangement while keeping photograph lettering facing forward.
    val topLeft = face.corners[if (rtl) 1 else 0]
    val topRight = face.corners[if (rtl) 0 else 1]
    val bottomLeft = face.corners[if (rtl) 2 else 3]
    return Matrix().apply {
        this[0, 0] = topRight.x - topLeft.x
        this[0, 1] = (topRight.y - topLeft.y) * size.height / size.width.coerceAtLeast(1f)
        this[1, 0] = (bottomLeft.x - topLeft.x) * size.width / size.height.coerceAtLeast(1f)
        this[1, 1] = bottomLeft.y - topLeft.y
        this[3, 0] = topLeft.x * size.width
        this[3, 1] = topLeft.y * size.height
    }
}
