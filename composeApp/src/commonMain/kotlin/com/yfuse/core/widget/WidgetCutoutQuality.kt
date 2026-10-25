package com.yfuse.core.widget

import kotlin.math.max

internal data class WidgetCutoutQuality(
    val top: Float,
    val bottom: Float,
    val left: Float,
    val right: Float,
    val foregroundFraction: Float,
)

/**
 * Conservative visual checks for an automatic poster cutout. A rejected mask should use the
 * original poster. These checks detect uncertain, fragmented or clipped masks; they cannot
 * establish that a semantic object is complete (for example, that every limb was retained).
 *
 * Bounds describe the largest connected foreground component in source-relative coordinates.
 * The mask is not modified, so the foreground stays aligned with the original image.
 */
internal fun assessWidgetCutoutMask(
    alpha: FloatArray,
    width: Int,
    height: Int,
): WidgetCutoutQuality? {
    // Inference uses 320 x 320. Bound work before allocating flood-fill buffers, including when
    // dimensions come from a corrupt cached result or would overflow an Int multiplication.
    if (width !in 16..1024 || height !in 16..1024) return null
    val pixelCountLong = width.toLong() * height.toLong()
    if (pixelCountLong > 1_048_576L || pixelCountLong != alpha.size.toLong()) return null
    val pixelCount = pixelCountLong.toInt()

    var foregroundCount = 0
    var uncertainCount = 0
    var softBackgroundCount = 0
    for (value in alpha) {
        if (!value.isFinite() || value < 0f || value > 1f) return null
        if (value >= 0.5f) foregroundCount++
        if (value > 0.1f && value < 0.9f) uncertainCount++
        if (value > 0.1f && value < 0.5f) softBackgroundCount++
    }
    val fraction = foregroundCount.toFloat() / pixelCount
    if (fraction < 0.06f || fraction > 0.60f) return null

    // Permit normal antialiased edges but reject diffuse subjects and broad background haze.
    // Comparing against the foreground also protects small subjects on a large fuzzy backdrop.
    if (uncertainCount > foregroundCount * 0.45f ||
        uncertainCount > pixelCount * 0.14f ||
        softBackgroundCount > foregroundCount * 0.22f
    ) {
        return null
    }

    val visited = BooleanArray(pixelCount)
    val queue = IntArray(foregroundCount)
    var largestCount = 0
    var secondLargestCount = 0
    var significantComponents = 0
    val significantThreshold = max(8, (foregroundCount * 0.01f).toInt())
    var largestLeft = 0
    var largestTop = 0
    var largestRight = 0
    var largestBottom = 0

    for (start in alpha.indices) {
        if (visited[start] || alpha[start] < 0.5f) continue
        var head = 0
        var tail = 1
        queue[0] = start
        visited[start] = true
        var left = width
        var top = height
        var right = 0
        var bottom = 0

        while (head < tail) {
            val index = queue[head++]
            val x = index % width
            val y = index / width
            if (x < left) left = x
            if (x > right) right = x
            if (y < top) top = y
            if (y > bottom) bottom = y

            // Eight-connected pixels retain thin diagonal limbs/straps as one component.
            for (neighborY in max(0, y - 1)..minOf(height - 1, y + 1)) {
                for (neighborX in max(0, x - 1)..minOf(width - 1, x + 1)) {
                    val neighbor = neighborY * width + neighborX
                    if (!visited[neighbor] && alpha[neighbor] >= 0.5f) {
                        visited[neighbor] = true
                        queue[tail++] = neighbor
                    }
                }
            }
        }

        if (tail >= significantThreshold) significantComponents++
        if (tail > largestCount) {
            secondLargestCount = largestCount
            largestCount = tail
            largestLeft = left
            largestTop = top
            largestRight = right + 1
            largestBottom = bottom + 1
        } else if (tail > secondLargestCount) {
            secondLargestCount = tail
        }
    }

    // A separate title or second subject must not rise with the intended foreground. A few
    // isolated pixels are tolerated; several smaller text-like pieces are rejected together.
    if (largestCount < foregroundCount * 0.94f ||
        secondLargestCount > pixelCount * 0.006f ||
        significantComponents > 2
    ) {
        return null
    }

    val left = largestLeft.toFloat() / width
    val top = largestTop.toFloat() / height
    val right = largestRight.toFloat() / width
    val bottom = largestBottom.toFloat() / height
    val subjectWidth = right - left
    val subjectHeight = bottom - top
    // Keep breathing room at all edges and a tall enough silhouette to cross the card frame.
    if (left < 0.02f ||
        right > 0.98f ||
        top < 0.02f ||
        bottom > 0.97f ||
        top > 0.60f ||
        subjectWidth > 0.90f ||
        subjectHeight < 0.24f ||
        subjectWidth > subjectHeight * 1.8f
    ) {
        return null
    }

    return WidgetCutoutQuality(
        top = top,
        bottom = bottom,
        left = left,
        right = right,
        foregroundFraction = fraction,
    )
}
