package com.yfuse.core.playback

/** One selectable optical-disc title/edition. Indices are zero based. */
data class PlaybackDiscTitle(
    val index: Int,
    /** Backend title/edition id when it differs from the zero-based UI index. */
    val id: Int? = null,
    /** Authored title when the disc/backend exposes one. */
    val title: String? = null,
    val isDefault: Boolean = false,
) {
    val label: String
        get() = title?.trim()?.takeIf(String::isNotEmpty) ?: "标题 ${index + 1}"
}

/** One chapter inside the active optical-disc title. */
data class PlaybackDiscChapter(
    val index: Int,
    val title: String? = null,
    /** Milliseconds from the beginning of the active title, when exposed by the backend. */
    val startMs: Long? = null,
) {
    val label: String
        get() = title?.trim()?.takeIf(String::isNotEmpty) ?: "章节 ${index + 1}"
}

/** Backend-neutral navigation state for DVD and Blu-ray sources. Indices are zero based. */
data class PlaybackDiscNavigationState(
    val kind: PlaybackDiscKind = PlaybackDiscKind.None,
    val titleCount: Int = 0,
    val selectedTitleIndex: Int = 0,
    val chapterCount: Int = 0,
    val selectedChapterIndex: Int = 0,
    /** Rich title metadata. Empty means the backend only exposed a count. */
    val titles: List<PlaybackDiscTitle> = emptyList(),
    /** Rich chapter metadata. Empty means the backend only exposed a count. */
    val chapters: List<PlaybackDiscChapter> = emptyList(),
    val menuSupported: Boolean = false,
    val menuActive: Boolean = false,
) {
    val effectiveTitleCount: Int get() = maxOf(titleCount, titles.size)

    val effectiveChapterCount: Int get() = maxOf(chapterCount, chapters.size)

    /** Always gives the control layer selectable rows, even on a count-only backend. */
    val titleOptions: List<PlaybackDiscTitle>
        get() {
            if (effectiveTitleCount <= 0) return emptyList()
            val byIndex = titles.associateBy(PlaybackDiscTitle::index)
            return List(effectiveTitleCount) { index -> byIndex[index] ?: PlaybackDiscTitle(index = index) }
        }

    /** Always gives the control layer selectable rows, even on a count-only backend. */
    val chapterOptions: List<PlaybackDiscChapter>
        get() {
            if (effectiveChapterCount <= 0) return emptyList()
            val byIndex = chapters.associateBy(PlaybackDiscChapter::index)
            return List(effectiveChapterCount) { index -> byIndex[index] ?: PlaybackDiscChapter(index = index) }
        }

    val selectedTitle: PlaybackDiscTitle?
        get() = titleOptions.getOrNull(selectedTitleIndex)

    val selectedChapter: PlaybackDiscChapter?
        get() = chapterOptions.getOrNull(selectedChapterIndex)

    val available: Boolean
        get() =
            kind != PlaybackDiscKind.None &&
                (effectiveTitleCount > 0 || effectiveChapterCount > 0 || menuSupported)
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
