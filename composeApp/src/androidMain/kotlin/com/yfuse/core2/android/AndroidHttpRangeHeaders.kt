package com.yfuse.core2.android

internal data class YHttpContentRange(
    val start: Long,
    val end: Long,
    val total: Long?,
)

internal fun parseContentRange(value: String?): YHttpContentRange? {
    val match = CONTENT_RANGE.matchEntire(value?.trim().orEmpty()) ?: return null
    val start = match.groupValues[1].toLongOrNull() ?: return null
    val end = match.groupValues[2].toLongOrNull() ?: return null
    val total = match.groupValues[3].takeUnless { it == "*" }?.toLongOrNull()
    if (start < 0L || end < start || total != null && total <= end) return null
    return YHttpContentRange(start, end, total)
}

internal fun parseUnsatisfiedContentRangeLength(value: String?): Long? {
    val match = UNSATISFIED_CONTENT_RANGE.matchEntire(value?.trim().orEmpty()) ?: return null
    return match.groupValues[1].toLongOrNull()?.takeIf { it >= 0L }
}

private val CONTENT_RANGE = Regex("bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)", RegexOption.IGNORE_CASE)
private val UNSATISFIED_CONTENT_RANGE = Regex("bytes\\s+\\*/(\\d+)", RegexOption.IGNORE_CASE)
