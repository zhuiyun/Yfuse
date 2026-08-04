package com.yfuse.core.util

/**
 * Small extended-grapheme helper for user-entered room text.
 *
 * Kotlin common strings are UTF-16. Counting [String.length] would therefore call 😀 two
 * characters and a joined family emoji many more. This covers surrogate pairs, combining
 * marks, variation selectors, skin tones, flags, keycaps and zero-width-joiner sequences —
 * the cases an Emoji-capable 30-character input must keep together.
 */
internal fun String.graphemeCount(): Int {
    var count = 0
    var index = 0
    while (index < length) {
        index = nextGraphemeEnd(index)
        count++
    }
    return count
}

internal fun String.takeGraphemes(max: Int): String {
    if (max <= 0 || isEmpty()) return ""
    var count = 0
    var end = 0
    while (end < length && count < max) {
        end = nextGraphemeEnd(end)
        count++
    }
    return substring(0, end)
}

internal fun String.takeGraphemesWithinUtf8Bytes(maxBytes: Int): String {
    if (maxBytes <= 0 || isEmpty()) return ""
    var cursor = 0
    while (cursor < length) {
        val next = nextGraphemeEnd(cursor)
        if (substring(0, next).encodeToByteArray().size > maxBytes) break
        cursor = next
    }
    return substring(0, cursor)
}

internal fun String.withoutControlCharacters(): String = filterNot { character ->
    character.code in 0x00..0x1F || character.code in 0x7F..0x9F
}

private fun String.nextGraphemeEnd(start: Int): Int {
    var cursor = codePointAt(start).end
    val first = codePointAt(start).value

    // National flags are pairs of regional indicators.
    if (first.isRegionalIndicator() && cursor < length) {
        val next = codePointAt(cursor)
        if (next.value.isRegionalIndicator()) cursor = next.end
    }

    cursor = consumeExtenders(cursor)
    while (cursor < length) {
        val joiner = codePointAt(cursor)
        if (joiner.value != ZERO_WIDTH_JOINER || joiner.end >= length) break
        val joined = codePointAt(joiner.end)
        cursor = consumeExtenders(joined.end)
    }
    return cursor
}

private fun String.consumeExtenders(from: Int): Int {
    var cursor = from
    while (cursor < length) {
        val point = codePointAt(cursor)
        if (!point.value.isGraphemeExtender()) break
        cursor = point.end
    }
    return cursor
}

private data class CodePoint(val value: Int, val end: Int)

private fun String.codePointAt(index: Int): CodePoint {
    val high = this[index].code
    if (high in highSurrogateRange && index + 1 < length) {
        val low = this[index + 1].code
        if (low in lowSurrogateRange) {
            return CodePoint(
                value = 0x10000 + ((high - 0xD800) shl 10) + (low - 0xDC00),
                end = index + 2,
            )
        }
    }
    return CodePoint(high, index + 1)
}

private fun Int.isRegionalIndicator(): Boolean = this in 0x1F1E6..0x1F1FF

private fun Int.isGraphemeExtender(): Boolean =
    this in 0x0300..0x036F ||
        this in 0x1AB0..0x1AFF ||
        this in 0x1DC0..0x1DFF ||
        this in 0x20D0..0x20FF ||
        this in 0xFE20..0xFE2F ||
        this in 0xFE00..0xFE0F ||
        this in 0xE0100..0xE01EF ||
        this in 0x1F3FB..0x1F3FF ||
        this in 0xE0020..0xE007F ||
        this == 0x20E3

private val highSurrogateRange = 0xD800..0xDBFF
private val lowSurrogateRange = 0xDC00..0xDFFF
private const val ZERO_WIDTH_JOINER = 0x200D
