package com.yfuse.core2.legacy

import com.yfuse.feature.player.DEFAULT_SUBTITLE_POSITION
import com.yfuse.feature.player.SubtitleAppearance

/**
 * The viewer's 字幕 settings, for a backend that draws subtitles inside its own video output.
 *
 * Core2's own routes publish cues and Core2Surface styles them in Compose. The libmpv
 * compatibility executor draws embedded subtitles itself, so the same settings have to reach it:
 * otherwise 字幕偏移, 字号, 位置 and 样式 report success on that route and change nothing.
 * [appearance] already carries 字幕亮度, as the player screen hands it over.
 */
internal data class YNativeSubtitleStyle(
    val offsetMs: Long = 0L,
    val scale: Float = 1f,
    val position: Float = DEFAULT_SUBTITLE_POSITION,
    val appearance: SubtitleAppearance = SubtitleAppearance(),
)

/** A player that hands [YNativeSubtitleStyle] to whichever of its backends draws subtitles itself. */
internal interface YNativeSubtitleStyleTarget {
    /**
     * Returns false only when the backend drawing subtitles rejected the style. A backend that is
     * not running yet keeps it and applies it once it is.
     */
    fun setNativeSubtitleStyle(style: YNativeSubtitleStyle): Boolean
}
