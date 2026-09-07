package com.yfuse.core.designsystem

import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

@Composable
fun rememberLoopingCarouselState(itemIds: List<String>): PagerState {
    val state =
        rememberPagerState(
            initialPage = loopingCarouselStartPage(itemIds.size),
            pageCount = { loopingCarouselPageCount(itemIds.size) },
        )
    var previousIds by rememberSaveable { mutableStateOf(itemIds) }
    LaunchedEffect(itemIds) {
        if (previousIds != itemIds) {
            val selectedId = previousIds.getOrNull(loopingCarouselItemIndex(state.settledPage, previousIds.size))
            val index = itemIds.indexOf(selectedId).coerceAtLeast(0)
            state.scrollToPage(loopingCarouselStartPage(itemIds.size) + index)
            previousIds = itemIds
        }
    }
    return state
}
