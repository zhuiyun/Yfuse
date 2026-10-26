package com.yfuse.feature.widgets

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

internal data class GalleryPoint(
    val x: Float,
    val y: Float,
)

/** Corners follow the artwork's top-left, top-right, bottom-right, bottom-left order. */
internal data class PrismGalleryFace(
    val index: Int,
    val corners: List<GalleryPoint>,
    val depth: Float,
    val shade: Float,
    val top: Boolean = false,
    val bottom: Boolean = false,
)

/** A closed solid, culled and depth-sorted using the same camera on both rotation axes. */
internal fun prismGalleryFaces(
    position: Float,
    count: Int,
    rtl: Boolean = false,
    pitchDegrees: Float = 0f,
): List<PrismGalleryFace> {
    if (count <= 0) return emptyList()
    val progress = position.finiteOrZero().coerceIn(0f, (count - 1).toFloat())
    val pitch = normalizedPrismPitch(pitchDegrees) * PI / 180
    val yaw = -(progress.toDouble() % 4.0) * PI / 2 - PI / 6
    val cosine = cos(yaw).toFloat()
    val sine = sin(yaw).toFloat()
    val cameraPitch = pitch - 22.0 * PI / 180
    // Renumber only the hidden side, even when the cube is turned upside down.
    val center = floor(progress.toDouble() + 1.0 / 3 + if (cos(cameraPitch) < 0) 2 else 0).toInt()

    fun rotate(vertex: PrismVertex): PrismVertex =
        PrismVertex(vertex.x * cosine + vertex.z * sine, vertex.y, -vertex.x * sine + vertex.z * cosine)
            .pitch(pitch)

    // Fit all eight vertices, so a diagonal turn cannot clip the top or bottom.
    val extent =
        listOf(-.5f, .5f)
            .flatMap { x ->
                listOf(-.5f, .5f).flatMap { y -> listOf(-.5f, .5f).map { z -> rotate(PrismVertex(x, y, z)) } }
            }.maxOf { abs(it.screenY()) }
    val scale = minOf(1f, .43f / (extent * .66f).coerceAtLeast(.0001f))
    val sides =
        (center - 1..center + 2).mapNotNull { logicalIndex ->
            val angle = (logicalIndex - progress.toDouble()) * PI / 2 - PI / 6
            val facing = cos(angle) * cos(cameraPitch)
            if (facing <= .00001) return@mapNotNull null
            val sideSine = sin(angle).toFloat()
            val sideCosine = cos(angle).toFloat()
            val vertices =
                listOf(
                    PrismVertex((sideSine - sideCosine) / 2, -.5f, (sideCosine + sideSine) / 2),
                    PrismVertex((sideSine + sideCosine) / 2, -.5f, (sideCosine - sideSine) / 2),
                    PrismVertex((sideSine + sideCosine) / 2, .5f, (sideCosine - sideSine) / 2),
                    PrismVertex((sideSine - sideCosine) / 2, .5f, (sideCosine + sideSine) / 2),
                ).map { it.pitch(pitch) }
            PrismGalleryFace(
                index = wrapGalleryIndex(logicalIndex, count),
                corners = vertices.map { it.project(rtl, scale) },
                depth = vertices.sumOf { it.depth().toDouble() }.toFloat() / 4,
                shade = .04f + .28f * (1f - facing.toFloat()),
            )
        }
    val caps =
        listOf(-.5f, .5f).mapNotNull { y ->
            val facing = (if (y < 0) -1 else 1) * sin(cameraPitch)
            if (facing <= .00001) return@mapNotNull null
            val vertices =
                listOf(
                    PrismVertex(-.5f, y, -.5f),
                    PrismVertex(.5f, y, -.5f),
                    PrismVertex(.5f, y, .5f),
                    PrismVertex(-.5f, y, .5f),
                ).map(::rotate)
            PrismGalleryFace(
                // Caps retain their photographs throughout a complete turn.
                index = wrapGalleryIndex(if (y < 0) 2 else 3, count),
                corners = vertices.map { it.project(rtl, scale) },
                depth = vertices.sumOf { it.depth().toDouble() }.toFloat() / 4,
                shade = .08f + .20f * (1f - facing.toFloat()),
                top = y < 0,
                bottom = y > 0,
            )
        }
    return (sides + caps).sortedBy { it.depth }
}

internal fun normalizedPrismPitch(degrees: Float): Float = ((degrees.finiteOrZero() + 180f) % 360f + 360f) % 360f - 180f

/** The footer and detail action follow the largest visible photograph, including either cap. */
internal fun prismGalleryFrontIndex(faces: List<PrismGalleryFace>): Int =
    faces
        .maxByOrNull { face ->
            abs(
                face.corners.indices.sumOf { index ->
                    val a = face.corners[index]
                    val b = face.corners[(index + 1) % face.corners.size]
                    (a.x * b.y - b.x * a.y).toDouble()
                },
            )
        }?.index ?: 0

/** Convex polygon hit testing includes edges but rejects the surrounding empty corners. */
internal fun galleryQuadContains(
    corners: List<GalleryPoint>,
    point: GalleryPoint,
): Boolean {
    if (corners.size != 4 || !point.x.isFinite() || !point.y.isFinite()) return false
    if (corners.any { !it.x.isFinite() || !it.y.isFinite() }) return false
    var positive = false
    var negative = false
    var area = 0f
    for (index in corners.indices) {
        val first = corners[index]
        val next = corners[(index + 1) % corners.size]
        val cross = (next.x - first.x) * (point.y - first.y) - (next.y - first.y) * (point.x - first.x)
        positive = positive || cross > .000001f
        negative = negative || cross < -.000001f
        if (positive && negative) return false
        area += first.x * next.y - next.x * first.y
    }
    return abs(area) > .000001f
}

/** One shallow, continuous wave; both ribbon edges use this same offset. */
internal fun ribbonGalleryWave(
    x: Float,
    phase: Float,
): Float = (.065 * sin(2 * PI * (x.finiteOrZero() + phase.finiteOrZero() * .18))).toFloat()

/** The centre film spans .28..72; each neighbouring film occupies another .44. */
internal fun ribbonGalleryIndexAt(
    x: Float,
    position: Float,
    count: Int,
    rtl: Boolean = false,
): Int {
    if (count <= 0) return -1
    val progress = position.finiteOrZero().coerceIn(0f, (count - 1).toFloat())
    val stageX = x.finiteOrZero().coerceIn(0f, 1f).let { if (rtl) 1f - it else it }
    // Resolve a shared edge toward the following item in either layout direction.
    // The tiny tolerance absorbs Float reflection error from (1 - x) in RTL.
    return floor(progress.toDouble() + (stageX.toDouble() - .5) / .44 + .500001)
        .toInt()
        .coerceIn(0, count - 1)
}

private data class PrismVertex(
    val x: Float,
    val y: Float,
    val z: Float,
) {
    fun pitch(angle: Double): PrismVertex =
        PrismVertex(
            x,
            y * cos(angle).toFloat() - z * sin(angle).toFloat(),
            y * sin(angle).toFloat() + z * cos(angle).toFloat(),
        )

    fun screenY(): Float = y * PrismCameraCosine - z * PrismCameraSine

    fun project(
        rtl: Boolean,
        scale: Float,
    ): GalleryPoint {
        val screenX = .5f + x * .59f * scale
        return GalleryPoint(
            x = if (rtl) 1f - screenX else screenX,
            y = .47f + screenY() * .66f * scale,
        )
    }

    fun depth(): Float = z * PrismCameraCosine + y * PrismCameraSine
}

private val PrismCameraCosine = cos(22.0 * PI / 180).toFloat()
private val PrismCameraSine = sin(-22.0 * PI / 180).toFloat()

private fun Float.finiteOrZero(): Float = if (isFinite()) this else 0f

private fun wrapGalleryIndex(
    index: Int,
    count: Int,
): Int = ((index % count) + count) % count
