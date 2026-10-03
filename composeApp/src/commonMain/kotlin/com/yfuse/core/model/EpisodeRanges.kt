package com.yfuse.core.model

/** Episodes per tab of the 选集 grid. */
const val EPISODE_RANGE_SIZE = 30

/**
 * From this many episodes a season is picked by number: one card per episode — a rail of 210dp
 * stills, a strip of 140dp ones — leaves 第 150 集 of a 短剧 a long fling away.
 */
const val EPISODE_GRID_MIN_COUNT = 31

/** Whether a list of [count] episodes is better picked from the number grid than from stills. */
fun prefersEpisodeGrid(count: Int): Boolean = count >= EPISODE_GRID_MIN_COUNT

/**
 * Whether a season opens on the 选集 grid rather than on its stills: a long one whose episodes
 * mostly run under five minutes — a 短剧, whose stills tell its episodes apart no better than
 * their numbers do. A long season of full-length episodes keeps its stills, a tap from the grid.
 */
fun opensOnEpisodeGrid(runtimesMs: List<Long?>): Boolean {
    if (!prefersEpisodeGrid(runtimesMs.size)) return false
    val known = runtimesMs.mapNotNull { isShortRuntime(it) }
    return known.isNotEmpty() && known.count { it } * 2 > known.size
}

/** Positions of [count] episodes in tabs of [size]: 0..29, 30..59, … — the last may be short. */
fun episodeRanges(
    count: Int,
    size: Int = EPISODE_RANGE_SIZE,
): List<IntRange> {
    if (count <= 0 || size <= 0) return emptyList()
    return (0 until count step size).map { start -> start..minOf(start + size, count) - 1 }
}

/** The tab holding [position]. */
fun episodeRangeIndex(
    position: Int,
    size: Int = EPISODE_RANGE_SIZE,
): Int = if (position < 0 || size <= 0) 0 else position / size

/**
 * 「1-30」: a tab named by the episode numbers at its ends, or by position where an end has none.
 * A tab of one episode is just its number.
 */
fun episodeRangeLabel(
    range: IntRange,
    numberAt: (Int) -> Int?,
): String {
    val first = numberAt(range.first) ?: (range.first + 1)
    val last = numberAt(range.last) ?: (range.last + 1)
    return if (range.first == range.last) "$first" else "$first-$last"
}

/**
 * The position of 第 [number] 集: the episode carrying that number, or — in a list without
 * numbers — the [number]th one. Null when there is no such episode.
 */
fun episodePositionForNumber(
    number: Int,
    numbers: List<Int?>,
): Int? {
    val numbered = numbers.indexOf(number)
    if (numbered >= 0) return numbered
    return if (numbers.all { it == null } && number in 1..numbers.size) number - 1 else null
}

/**
 * Whether [typed], the digits keyed on a remote so far, could still become a larger episode
 * number with one more: 1 of 120 episodes could be 12 or 120, so the jump waits for the next
 * digit; 13 could not, so it goes at once.
 */
fun typedEpisodeNumberMayGrow(
    typed: Int,
    highestNumber: Int,
): Boolean = typed in 1..(highestNumber / 10)
