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

    fun apply(
        comments: List<DanmakuComment>,
        merge: Boolean,
        blockedWords: List<String>,
    ): List<DanmakuComment> {
        val blocked = blockedWords.mapNotNull { it.trim().lowercase().takeIf(String::isNotEmpty) }
        val kept = if (blocked.isEmpty()) {
            comments
        } else {
            comments.filterNot { comment ->
                val text = comment.text.lowercase()
                blocked.any { it in text }
            }
        }
        return if (merge) merge(kept) else kept
    }

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
