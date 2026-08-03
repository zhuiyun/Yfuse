package com.yfuse.core.designsystem

/**
 * A pager cannot literally have an infinite number of pages. Starting near the middle
 * gives both swipe directions enough room for the lifetime of the app while each page is
 * mapped back to a real item.
 */
internal const val LoopingCarouselPageCount: Int = Int.MAX_VALUE

internal fun loopingCarouselPageCount(itemCount: Int): Int =
    if (itemCount > 1) LoopingCarouselPageCount else 1

internal fun loopingCarouselStartPage(itemCount: Int): Int {
    if (itemCount <= 1) return 0
    val middle = LoopingCarouselPageCount / 2
    return middle - middle % itemCount
}

internal fun loopingCarouselItemIndex(page: Int, itemCount: Int): Int =
    if (itemCount <= 0) 0 else page.mod(itemCount)

/** Returns the nearest virtual page that represents [targetIndex]. */
internal fun loopingCarouselTargetPage(
    currentPage: Int,
    targetIndex: Int,
    itemCount: Int,
): Int {
    if (itemCount <= 1) return 0

    val currentIndex = loopingCarouselItemIndex(currentPage, itemCount)
    var delta = targetIndex.mod(itemCount) - currentIndex
    if (delta > itemCount / 2) delta -= itemCount
    if (delta < -itemCount / 2) delta += itemCount
    return (currentPage + delta).coerceIn(0, LoopingCarouselPageCount - 1)
}
