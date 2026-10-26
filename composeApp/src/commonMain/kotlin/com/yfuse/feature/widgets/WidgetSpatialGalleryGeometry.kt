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
)

/**
 * The visible surfaces of one cube, projected by the same orthographic camera.
 * Adjacent surfaces share world vertices, including while a new film rotates into view.
 * Logical neighbours wrap only for artwork; the gallery's selection remains bounded.
 */
internal fun prismGalleryFaces(
    position: Float,
    count: Int,
    rtl: Boolean = false,
): List<PrismGalleryFace> {
    if (count <= 0) return emptyList()
    val progress = position.finiteOrZero().coerceIn(0f, (count - 1).toFloat())
    val base = floor(progress.toDouble()).toInt()
    val fraction = progress - base
    val sides =
        (base - 1..base + 2).mapNotNull { logicalIndex ->
            val angle = ((logicalIndex - base - fraction) * PI / 2) - PI / 6
            val facing = cos(angle)
            if (facing <= 0.00001) return@mapNotNull null
            val sine = sin(angle).toFloat()
            val cosine = facing.toFloat()
            val vertices =
                listOf(
                    PrismVertex((sine - cosine) / 2, -.5f, (cosine + sine) / 2),
                    PrismVertex((sine + cosine) / 2, -.5f, (cosine - sine) / 2),
                    PrismVertex((sine + cosine) / 2, .5f, (cosine - sine) / 2),
                    PrismVertex((sine - cosine) / 2, .5f, (cosine + sine) / 2),
                )
            PrismGalleryFace(
                index = wrapGalleryIndex(logicalIndex, count),
                corners = vertices.map { it.project(rtl) },
                depth = vertices.sumOf { it.depth().toDouble() }.toFloat() / 4,
                shade = .04f + .28f * (1f - cosine),
            )
        }

    // Keep this orientation continuous across integer positions: changing the side
    // enumeration must never turn the top texture by an extra quarter revolution.
    val topAngle = -(progress.toDouble() % 4.0) * PI / 2 - PI / 6
    val sine = sin(topAngle).toFloat()
    val cosine = cos(topAngle).toFloat()
    val topVertices =
        listOf(
            PrismVertex(-.5f, -.5f, -.5f),
            PrismVertex(.5f, -.5f, -.5f),
            PrismVertex(.5f, -.5f, .5f),
            PrismVertex(-.5f, -.5f, .5f),
        ).map { vertex ->
            PrismVertex(vertex.x * cosine + vertex.z * sine, vertex.y, -vertex.x * sine + vertex.z * cosine)
        }
    val sideIndices = sides.map { it.index }.toSet()
    val topIndex =
        (0 until minOf(count, 4))
            .map { wrapGalleryIndex(base + 2 + it, count) }
            .firstOrNull { it !in sideIndices }
            ?: wrapGalleryIndex(base + 1, count)
    val top =
        PrismGalleryFace(
            index = topIndex,
            corners = topVertices.map { it.project(rtl) },
            depth = topVertices.sumOf { it.depth().toDouble() }.toFloat() / 4,
            shade = .12f,
            top = true,
        )
    return (sides + top).sortedBy { it.depth }
}

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
    fun project(rtl: Boolean): GalleryPoint {
        val screenX = .5f + x * .59f
        return GalleryPoint(
            x = if (rtl) 1f - screenX else screenX,
            y = .49f + (y * PrismCameraCosine - z * PrismCameraSine) * .66f,
        )
    }

    fun depth(): Float = z * PrismCameraCosine + y * PrismCameraSine
}

private val PrismCameraCosine = cos(22.0 * PI / 180).toFloat()
private val PrismCameraSine = sin(22.0 * PI / 180).toFloat()

private fun Float.finiteOrZero(): Float = if (isFinite()) this else 0f

private fun wrapGalleryIndex(
    index: Int,
    count: Int,
): Int = ((index % count) + count) % count
