package com.yfuse.core.model

enum class PlaybackSegmentType(
    val skipLabel: String,
) {
    Intro("跳过片头"),
    Credits("跳过片尾"),
}

/**
 * A server-provided segment in milliseconds. Credits usually have no explicit
 * end; the player then advances to the next queue item or seeks to the end.
 */
data class PlaybackSegment(
    val type: PlaybackSegmentType,
    val startMs: Long,
    val endMs: Long?,
) {
    fun contains(
        positionMs: Long,
        durationMs: Long,
    ): Boolean {
        val end = endMs ?: durationMs.takeIf { it > 0L } ?: Long.MAX_VALUE
        return positionMs in startMs until end
    }
}

/**
 * An ordinary chapter of the file, as its container named it: where it starts and what it is
 * called. Only named chapters exist here — see [namedPlaybackChapters] for what is left out.
 */
data class PlaybackChapter(
    val startMs: Long,
    val name: String,
)

/**
 * The chapters worth a mark on the progress bar, from a server's list or the container's own.
 *
 * A name that only counts — "Chapter 3", "第 3 章", "00:12:00", which muxers and servers write when
 * the file had none — says nothing its position does not, and is left out with the unnamed ones,
 * as is a chapter at or past the end of the runtime. Two chapters at one position keep the first.
 */
fun namedPlaybackChapters(
    chapters: Sequence<Pair<Long, String?>>,
    runtimeMs: Long?,
): List<PlaybackChapter> =
    chapters
        .mapNotNull { (start, rawName) ->
            val name = rawName?.trim()?.takeIf { it.isNotEmpty() && !isCountingChapterName(it) }
            val startMs = start.coerceAtLeast(0L)
            if (name == null || (runtimeMs != null && startMs >= runtimeMs)) null else PlaybackChapter(startMs, name)
        }.sortedBy(PlaybackChapter::startMs)
        .distinctBy(PlaybackChapter::startMs)
        .toList()

/** "Chapter 3", "Ch. 12", "第 3 章", "チャプター 2", a bare number or a timestamp: a name that only counts. */
fun isCountingChapterName(name: String): Boolean = COUNTING_CHAPTER_NAME.matches(name.trim())

private val COUNTING_CHAPTER_NAME =
    Regex(
        "(?:(?:chapter|chap|ch|kapitel|chapitre|cap[ií]tulo|capitolo|глава|章节|章節|チャプター|챕터)" +
            "\\.?\\s*#?\\s*\\d+)|(?:第\\s*\\d+\\s*[章节節话話幕])|[\\d\\s.:,-]+",
        RegexOption.IGNORE_CASE,
    )
