package com.yfuse.core.designsystem

import androidx.compose.ui.unit.dp

/** Shared media geometry for 首页 and 媒体库 so the two roots cannot drift apart. */
object MediaSizing {
    /** Full-width featured carousel used by both root screens. */
    val heroHeight = 432.dp

    /** Landscape resume/history card used by 继续观看 / 播放记录. */
    val landscapeCardWidth = 190.dp
    val landscapeCardHeight = 114.dp

    /** Portrait poster rail width used by recommendation/library shelves. */
    val posterRailWidth = 104.dp
}
