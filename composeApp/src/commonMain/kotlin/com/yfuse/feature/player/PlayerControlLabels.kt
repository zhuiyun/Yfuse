package com.yfuse.feature.player

import com.yfuse.core.cast.CastState
import com.yfuse.core.cast.formatDlnaTime
import com.yfuse.core.playback.PlaybackDiscNavigationState

/** 「标题 N」, or the disc's own edition/playlist name where it authored one. */
internal fun discTitleToast(
    navigation: PlaybackDiscNavigationState,
    index: Int,
): String = navigation.titleOptions.getOrNull(index)?.label ?: "标题 ${index + 1}"

/**
 * 「第 N 章 · 章节名」.
 *
 * The authored name is appended only when the disc carries one: the chapter's own label falls
 * back to 「章节 N」, which next to the number would just say the same thing twice.
 */
internal fun discChapterToast(
    navigation: PlaybackDiscNavigationState,
    index: Int,
): String {
    val authored =
        navigation.chapterOptions
            .getOrNull(index)
            ?.title
            ?.trim()
            ?.takeIf(String::isNotEmpty)
    return "第 ${index + 1} 章" + authored?.let { " · $it" }.orEmpty()
}

/**
 * What a tap on the 弹幕 key says it did. The tap used to change nothing but a faint fill behind
 * the key, and when no comments came — the episode not matched, a source that did not answer —
 * nothing said why: the reason sat in the panel, behind a held press.
 *
 * Off is said at once. On waits for the load, so it can say how many comments matched or why none
 * show. [unmatched] is the source answering with no episode for this one, which 搜索弹幕 in the
 * panel puts right.
 */
internal fun danmakuKeyToast(
    enabled: Boolean,
    count: Int = 0,
    error: String? = null,
    unmatched: Boolean = false,
): String =
    when {
        !enabled -> "弹幕已关闭"
        count > 0 -> "弹幕已开启 · 已匹配 $count 条"
        unmatched -> "弹幕已开启，但没有匹配到这一集，按住弹幕键可手动搜索"
        error != null -> "弹幕已开启，但加载失败：$error"
        else -> "弹幕已开启，这一集还没有弹幕"
    }

/**
 * The cast panel's position line: where the receiver is, and of how long once that is known — but
 * only after the receiver has confirmed it. Null while nothing is being cast to.
 */
internal fun castPositionLabel(cast: CastState): String? =
    cast.activeDevice?.let {
        if (!cast.positionConfirmed) {
            "等待接收端确认"
        } else {
            buildString {
                append(formatDlnaTime(cast.positionMs))
                if (cast.durationMs > 0L) {
                    append(" / ")
                    append(formatDlnaTime(cast.durationMs))
                }
            }
        }
    }

/** What the receiver being cast to can do, for the cast panel; null while nothing is being cast to. */
internal fun castCapabilitiesLabel(cast: CastState): String? =
    cast.activeDevice?.let {
        val capabilities = cast.capabilities
        "播放 ${capabilities.playPause.label} · " +
            "跳转 ${capabilities.seek.label} · " +
            "音量 ${capabilities.volume.label} · " +
            "轨道 ${capabilities.trackSelection.label} · " +
            "队列 ${capabilities.queue.label} · " +
            "DV ${capabilities.dolbyVision.label} · " +
            "Atmos ${capabilities.dolbyAtmos.label}"
    }
