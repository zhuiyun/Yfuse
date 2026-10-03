package com.yfuse.core.data

/**
 * The newest entry of each work, newest first, at most [limit] of them.
 *
 * Local history is kept per episode. Counting entries before merging them by series let one
 * binge — sixty one-minute episodes of a single 短剧 — fill every slot of 继续观看 and 下一集,
 * and every show watched before it fell off. [workKeyOf] names the work an entry belongs to (an
 * episode's series, a film itself); entries whose work is unknown are skipped.
 */
internal fun <T> newestPerWork(
    entries: Iterable<T>,
    limit: Int,
    workKeyOf: (T) -> String?,
): List<T> {
    if (limit <= 0) return emptyList()
    val seen = HashSet<String>()
    val newest = ArrayList<T>()
    for (entry in entries) {
        if (newest.size >= limit) break
        val work = workKeyOf(entry)?.takeIf(String::isNotBlank) ?: continue
        if (seen.add(work)) newest += entry
    }
    return newest
}
