package com.yfuse.core.designsystem

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.horizontalScrollAxisRange
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription

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

/**
 * Replaces the pager's billion-page range with the real logical collection for TalkBack.
 * Scroll actions still come from [androidx.compose.foundation.pager.HorizontalPager]; only
 * the exposed range and spoken state are corrected here.
 */
fun Modifier.loopingCarouselSemantics(currentPage: Int, itemCount: Int): Modifier {
    if (itemCount <= 0) return this
    val logicalIndex = loopingCarouselItemIndex(currentPage, itemCount)
    return semantics {
        collectionInfo = CollectionInfo(rowCount = 1, columnCount = itemCount)
        stateDescription = loopingCarouselStateDescription(currentPage, itemCount)
        if (itemCount > 1) {
            horizontalScrollAxisRange = ScrollAxisRange(
                value = { logicalIndex.toFloat() },
                maxValue = { (itemCount - 1).toFloat() },
                reverseScrolling = false,
            )
        }
    }
}

internal fun loopingCarouselStateDescription(currentPage: Int, itemCount: Int): String {
    val logicalIndex = loopingCarouselItemIndex(currentPage, itemCount)
    return "第 ${logicalIndex + 1} 项，共 $itemCount 项"
}

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
