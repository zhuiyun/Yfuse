package com.yfuse.core.data

/**
 * What is worth putting on screen out of the fourteen thousand comments a popular episode
 * carries.
 *
 * Two problems, both of which make 弹幕 unusable long before the count does:
 *
 * **The same line, hundreds of times.** A moment everyone reacts to produces one sentence
 * repeated across a minute of timeline. Rendered literally it is a wall — every lane full
 * of the same six characters, and the comments that actually said something buried under
 * it. Collapsing a run into one comment with a count keeps the information ("a lot of
 * people said this") and returns the lanes.
 *
 * **The lines you never want to see.** Spoilers, a running joke that stopped being funny,
 * a bot. A word list is blunt but it is the only tool that works without reading everything
 * first, and it is what every other player offers.
 *
 * Both are pure functions over the loaded list, applied once when it arrives rather than
 * per frame — the overlay is already doing lane allocation sixty times a second.
 */
object DanmakuFilter {
    /**
     * How far apart two identical lines can be and still be the same moment.
     *
     * Long enough to catch a reaction spreading through a scene, short enough that a
     * catchphrase in episode-opening and episode-closing stays two separate comments.
     */
    const val MERGE_WINDOW_MS = 20_000L

    /**
     * Starts a block-list entry that means 屏蔽同类 rather than a word: the line after the mark and
     * every way people type it — "前方高能！！！", "前方 高能", "ＡＷＳＬ", "哈哈哈哈哈". Comments carry
     * no sender to block by, so near-identical text is the only kind of "the same" there is.
     *
     * It lives in the same list so it syncs, and is removed, like any word; a build that predates
     * the mark reads it as a word nobody types, which blocks nothing.
     */
    const val SIMILAR_MARK = "≈"

    /** The longest [similarKey] an entry keeps, so the mark and key fit one stored word. */
    private const val SIMILAR_KEY_MAX = MAX_DANMAKU_SYNC_BLOCKED_WORD_CHARS - SIMILAR_MARK.length

    fun apply(
        comments: List<DanmakuComment>,
        merge: Boolean,
        blockedWords: List<String>,
    ): List<DanmakuComment> {
        val entries = blockedWords.mapNotNull { it.trim().takeIf(String::isNotEmpty) }
        val blocked = entries.filterNot { it.startsWith(SIMILAR_MARK) }.map { it.lowercase() }
        val similar =
            entries
                .filter { it.startsWith(SIMILAR_MARK) }
                .map { similarKey(it.removePrefix(SIMILAR_MARK)) }
                .filter(String::isNotEmpty)
        val kept =
            if (blocked.isEmpty() && similar.isEmpty()) {
                comments
            } else {
                comments.filterNot { comment ->
                    val text = comment.text.lowercase()
                    blocked.any { it in text } ||
                        (
                            similar.isNotEmpty() &&
                                similarKey(comment.text).let { key -> similar.any { similarMatches(key, it) } }
                        )
                }
            }
        return if (merge) merge(kept) else kept
    }

    /** The 屏蔽同类 entry for [text], or null when nothing of it is left to compare. */
    fun similarRule(text: String): String? =
        similarKey(text)
            .takeIf(String::isNotEmpty)
            ?.let { SIMILAR_MARK + it.take(SIMILAR_KEY_MAX) }

    /**
     * What stays of a line once the ways of typing it are gone: full-width letters folded, case and
     * spacing dropped, punctuation dropped when there are words to keep, and every run of one
     * character cut to one — "哈哈哈" and "哈哈" and "2333" and "233333" are the same reaction.
     */
    fun similarKey(text: String): String {
        val folded =
            buildString(text.length) {
                text.forEach { c -> append(if (c in '！'..'～') c - FULL_WIDTH_OFFSET else c) }
            }.lowercase()
        val words = folded.any(Char::isLetterOrDigit)
        val kept = folded.filter { if (words) it.isLetterOrDigit() else !it.isWhitespace() }
        return buildString(kept.length) {
            var previous = ""
            var at = 0
            while (at < kept.length) {
                // A pair of surrogates is one character: an emoji repeated is a run like any other.
                val end = if (kept[at].isHighSurrogate() && at + 1 < kept.length) at + 2 else at + 1
                val unit = kept.substring(at, end)
                if (unit != previous) append(unit)
                previous = unit
                at = end
            }
        }
    }

    /** A key cut to [SIMILAR_KEY_MAX] stands for every line that starts the same way. */
    private fun similarMatches(
        key: String,
        rule: String,
    ): Boolean = key == rule || (rule.length >= SIMILAR_KEY_MAX && key.startsWith(rule))

    /**
     * Collapses runs of the same line into the earliest of them, carrying a count.
     *
     * The earliest rather than the median or the last: a comment is a reaction to something
     * that just happened on screen, so the first one is the one whose timing is right. The
     * rest were people typing.
     */
    fun merge(
        comments: List<DanmakuComment>,
        windowMs: Long = MERGE_WINDOW_MS,
    ): List<DanmakuComment> {
        if (comments.size < 2) return comments
        // Keyed on the text alone, so the same line said in two colours still merges —
        // the colour is the sender's choice and not part of what was said.
        val runs = HashMap<String, MutableList<Int>>()
        val counts = IntArray(comments.size) { 1 }
        val dropped = BooleanArray(comments.size)
        comments.forEachIndexed { index, comment ->
            val key = comment.text.trim()
            val open = runs.getOrPut(key) { mutableListOf() }
            // The list is sorted by time, so only the most recent head of this run can
            // still be inside the window.
            val head = open.lastOrNull()
            if (head != null && comment.timeMs - comments[head].timeMs <= windowMs) {
                counts[head]++
                dropped[index] = true
            } else {
                open += index
            }
        }
        return comments.mapIndexedNotNull { index, comment ->
            if (dropped[index]) null else comment.copy(repeats = counts[index])
        }
    }
}

/** From a full-width form (U+FF01..U+FF5E) to the ASCII character it stands for. */
private const val FULL_WIDTH_OFFSET = 0xFEE0
