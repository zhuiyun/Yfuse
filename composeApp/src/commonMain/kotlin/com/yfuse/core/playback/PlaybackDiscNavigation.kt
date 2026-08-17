package com.yfuse.core.playback

/** One selectable optical-disc title/edition. Indices are zero based. */
data class PlaybackDiscTitle(
    val index: Int,
    /** Backend title/edition id when it differs from the zero-based UI index. */
    val id: Int? = null,
    /** Authored title when the disc/backend exposes one. */
    val title: String? = null,
    /** Blu-ray playlist number when a backend exposes an MPLS filename/path in its title metadata. */
    val playlistNumber: Int? = null,
    val isDefault: Boolean = false,
) {
    val playlistLabel: String?
        get() = playlistNumber?.takeIf { it >= 0 }?.let { "${it.toString().padStart(5, '0')}.mpls" }

    val label: String
        get() =
            title?.trim()?.takeIf(String::isNotEmpty)
                ?: playlistLabel
                ?: "标题 ${index + 1}"
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

    val timeLabel: String?
        get() = startMs?.takeIf { it >= 0L }?.let(::formatDiscNavigationTime)
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

/** Extracts a Blu-ray MPLS number only from explicit `12345.mpls`/`mpls/12345` style hints. */
internal fun mplsPlaylistNumber(hint: String?): Int? {
    val value = hint?.trim()?.takeIf(String::isNotEmpty) ?: return null
    return MPLS_FILE_REGEX.find(value)?.groupValues?.getOrNull(1)?.toIntOrNull()
        ?: MPLS_PREFIX_REGEX.find(value)?.groupValues?.getOrNull(1)?.toIntOrNull()
}

private fun formatDiscNavigationTime(positionMs: Long): String {
    val totalSeconds = positionMs.coerceAtLeast(0L) / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "${hours.toString().padStart(2, '0')}:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    } else {
        "${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    }
}

private val MPLS_FILE_REGEX = Regex("(\\d{1,5})\\.mpls", RegexOption.IGNORE_CASE)
private val MPLS_PREFIX_REGEX = Regex("mpls[/\\\\:_ -]*(\\d{1,5})", RegexOption.IGNORE_CASE)

/** Returns the disc root when a server points at `BDMV/index.bdmv` instead of its parent. */
internal fun bluRayDiscRoot(path: String): String {
    val marker = path.lastIndexOf("/BDMV", ignoreCase = true)
    return if (marker > 0) path.substring(0, marker) else path
}
