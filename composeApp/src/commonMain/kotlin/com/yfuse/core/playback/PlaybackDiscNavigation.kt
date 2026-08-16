package com.yfuse.core.playback

/** Backend-neutral navigation state for DVD and Blu-ray sources. Indices are zero based. */
data class PlaybackDiscNavigationState(
    val kind: PlaybackDiscKind = PlaybackDiscKind.None,
    val titleCount: Int = 0,
    val selectedTitleIndex: Int = 0,
    val chapterCount: Int = 0,
    val selectedChapterIndex: Int = 0,
    val menuSupported: Boolean = false,
    val menuActive: Boolean = false,
) {
    val available: Boolean
        get() = kind != PlaybackDiscKind.None && (titleCount > 0 || chapterCount > 0 || menuSupported)
}

enum class PlaybackDiscMenuCommand {
    ShowMenu,
    Back,
    Up,
    Down,
    Left,
    Right,
    Select,
}

/** Returns the disc root when a server points at `BDMV/index.bdmv` instead of its parent. */
internal fun bluRayDiscRoot(path: String): String {
    val marker = path.lastIndexOf("/BDMV", ignoreCase = true)
    return if (marker > 0) path.substring(0, marker) else path
}
