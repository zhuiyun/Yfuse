package com.yfuse.core.widget

internal data class WidgetCutoutQuality(
    val top: Float,
    val bottom: Float,
    val left: Float,
    val right: Float,
    val foregroundFraction: Float,
)

/** Find a substantial connected silhouette rather than using stray lettering as the crop anchor. */
internal fun dominantWidgetCutoutSubject(
    width: Int,
    height: Int,
    pixels: IntArray,
): WidgetCutoutQuality? {
    require(width in 1..256 && height in 1..256 && pixels.size == width * height)
    val visited = BooleanArray(pixels.size)
    val queue = IntArray(pixels.size)
    var largest = 0
    var result: WidgetCutoutQuality? = null
    for (seed in pixels.indices) {
        if (visited[seed] || pixels[seed] ushr 24 < 128) continue
        var head = 0
        var tail = 1
        queue[0] = seed
        visited[seed] = true
        var left = width
        var right = 0
        var top = height
        var bottom = 0

        fun enqueue(index: Int) {
            if (!visited[index] && pixels[index] ushr 24 >= 128) {
                visited[index] = true
                queue[tail++] = index
            }
        }
        while (head < tail) {
            val index = queue[head++]
            val x = index % width
            val y = index / width
            left = minOf(left, x)
            right = maxOf(right, x + 1)
            top = minOf(top, y)
            bottom = maxOf(bottom, y + 1)
            if (x > 0) enqueue(index - 1)
            if (x + 1 < width) enqueue(index + 1)
            if (y > 0) enqueue(index - width)
            if (y + 1 < height) enqueue(index + width)
        }
        val spanWidth = right - left
        val spanHeight = bottom - top
        if (tail <= largest ||
            tail < pixels.size * .06f ||
            spanHeight < height * .38f ||
            spanWidth < width * .22f
        ) {
            continue
        }
        // An almost full-width, flat opaque top is a rectangular matte, not a useful overhang.
        val bandHeight = maxOf(1, spanHeight / 10)
        val topRows = IntArray(bandHeight)
        for (index in 0 until tail) {
            val y = queue[index] / width - top
            if (y < bandHeight) topRows[y]++
        }
        if (topRows.count { it >= width * .85f } >= bandHeight * .8f) continue
        largest = tail
        result =
            WidgetCutoutQuality(
                top.toFloat() / height,
                bottom.toFloat() / height,
                left.toFloat() / width,
                right.toFloat() / width,
                tail.toFloat() / pixels.size,
            )
    }
    return result
}
