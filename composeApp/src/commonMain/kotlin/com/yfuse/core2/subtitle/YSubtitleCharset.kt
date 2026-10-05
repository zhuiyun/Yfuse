package com.yfuse.core2.subtitle

/** Text encodings that downloaded subtitle sidecars arrive in. */
enum class YSubtitleCharset(
    vararg names: String,
) {
    Utf8("UTF-8"),
    Utf16Le("UTF-16LE"),
    Utf16Be("UTF-16BE"),

    /** Simplified Chinese: a superset of GBK and GB2312. */
    Gb18030("GB18030", "GBK"),

    /** Traditional Chinese, with the Hong Kong supplement for Cantonese characters. */
    Big5("Big5", "Big5-HKSCS"),

    /** Japanese as Windows writes it (CP932, Shift_JIS with the NEC and IBM additions). */
    ShiftJis("windows-31j", "Shift_JIS"),
    EucJp("EUC-JP"),

    /** Korean as Windows writes it (CP949, a superset of EUC-KR). */
    EucKr("x-windows-949", "windows-949", "EUC-KR"),

    /** Cyrillic. */
    Windows1251("windows-1251"),

    /** Western European. */
    Windows1252("windows-1252"),
    ;

    /** Java charset names that decode it, preferred first; a platform may lack the later ones. */
    val charsetNames: List<String> = names.toList()
}

/** Decodes bytes in a legacy encoding with the platform's charsets. */
fun interface YLegacyTextDecoder {
    /**
     * Null when the platform has no charset named [charsetName], or when [strict] decoding meets a
     * malformed or unmappable byte. Lenient decoding substitutes such bytes instead.
     */
    fun decode(
        data: ByteArray,
        charsetName: String,
        strict: Boolean,
    ): String?
}

/**
 * Decodes a downloaded subtitle sidecar, whose encoding no header states.
 *
 * Unicode is decoded here. The legacy encodings [detectSubtitleCharset] names need the platform's
 * [legacyDecoder]; without one their bytes are read as UTF-8. [traditionalHint] (a 繁 or `cht`
 * label) settles Big5 against GBK when the bytes alone do not.
 */
fun decodeExternalSubtitleText(
    data: ByteArray,
    traditionalHint: Boolean = false,
    legacyDecoder: YLegacyTextDecoder? = null,
): String {
    val candidates = subtitleCharsetCandidates(data, traditionalHint)
    val text =
        when (candidates.first()) {
            YSubtitleCharset.Utf8 -> data.decodeToString()
            YSubtitleCharset.Utf16Le -> data.decodeUtf16(littleEndian = true)
            YSubtitleCharset.Utf16Be -> data.decodeUtf16(littleEndian = false)
            else -> legacyDecoder?.let { decodeLegacy(data, candidates, it) } ?: data.decodeToString()
        }
    return text.removePrefix("\uFEFF")
}

/**
 * The first candidate name that decodes every byte, else the first the platform has, substituting
 * what it cannot decode. One damaged byte in a GBK file should cost one character, not turn the
 * whole file into Big5 nonsense, so a second encoding is a candidate only where the bytes never
 * chose the first.
 */
private fun decodeLegacy(
    data: ByteArray,
    candidates: List<YSubtitleCharset>,
    decoder: YLegacyTextDecoder,
): String? =
    candidates.firstNotNullOfOrNull { charset ->
        charset.charsetNames.firstNotNullOfOrNull { decoder.decode(data, it, strict = true) }
    } ?: candidates.first().charsetNames.firstNotNullOfOrNull { decoder.decode(data, it, strict = false) }

/**
 * Picks the encoding of a subtitle file from its bytes.
 *
 * A byte-order mark is trusted, then BOM-less UTF-16 and UTF-8 are recognised; UTF-8 survives a
 * few stray bytes, which legacy text never resembles. Anything else is a legacy encoding.
 * High bytes that stand alone among ASCII, or that break the double-byte pattern, are a Windows
 * single-byte code page: Cyrillic when they are most of the letters, Western otherwise.
 * Double-byte text is told apart by which byte ranges it uses (see [LegacyByteStatistics]), with
 * simplified Chinese, the common case for this app, as the fallback.
 */
fun detectSubtitleCharset(
    data: ByteArray,
    traditionalHint: Boolean = false,
): YSubtitleCharset = subtitleCharsetCandidates(data, traditionalHint).first()

/**
 * [detectSubtitleCharset]'s pick, followed by GB18030 when only [traditionalHint] chose Big5: a
 * file labelled traditional that Big5 cannot decode is simplified text under the wrong label.
 */
private fun subtitleCharsetCandidates(
    data: ByteArray,
    traditionalHint: Boolean,
): List<YSubtitleCharset> {
    byteOrderMark(data)?.let { return listOf(it) }
    bomlessUtf16(data)?.let { return listOf(it) }
    if (looksLikeUtf8(data)) return listOf(YSubtitleCharset.Utf8)

    val bytes = LegacyByteStatistics.of(data)
    val pairs = bytes.pairs
    val enoughPairs = pairs >= MIN_PAIRS
    val big5Shaped = bytes.nonBig5Pairs * 100 <= pairs * MAX_NON_BIG5_PERCENT
    val charset =
        when {
            bytes.singleByte -> if (bytes.cyrillic) YSubtitleCharset.Windows1251 else YSubtitleCharset.Windows1252
            enoughPairs && bytes.shiftJisKanaPairs * 100 >= pairs * KANA_PERCENT -> YSubtitleCharset.ShiftJis
            big5Shaped && enoughPairs && bytes.lowTrailPairs * 100 >= pairs * BIG5_LOW_TRAIL_PERCENT ->
                YSubtitleCharset.Big5
            big5Shaped && traditionalHint -> return listOf(YSubtitleCharset.Big5, YSubtitleCharset.Gb18030)
            enoughPairs &&
                bytes.eucJpKanaPairs * 100 >= pairs * EUC_JP_KANA_PERCENT &&
                bytes.lowTrailPairs * 100 <= pairs * MAX_EUC_JP_LOW_TRAIL_PERCENT -> YSubtitleCharset.EucJp
            bytes.gb2312Pairs >= MIN_KOREAN_PAIRS &&
                bytes.hangulRowPairs * 100 >= bytes.gb2312Pairs * HANGUL_ROW_PERCENT &&
                bytes.spacedPairs * 100 >= bytes.gb2312Pairs * KOREAN_SPACING_PERCENT -> YSubtitleCharset.EucKr
            else -> YSubtitleCharset.Gb18030
        }
    return listOf(charset)
}

/**
 * Byte shapes of legacy text. Pairs are read GBK-style: a lead byte 0x81-0xFE and the byte after
 * it, or a GB18030 four-byte sequence.
 *
 * - A lead followed by a control, space, punctuation, digit, 0x7F or 0xFF is a broken pair, which
 *   no double-byte encoding writes; Cyrillic words of odd length make them all the time.
 * - GB2312, which simplified-Chinese GBK subtitles are written in almost entirely, uses leads
 *   0xB0-0xF7 with trails 0xA1-0xFE. Common characters are spread over the whole range (是 0xCAC7,
 *   我 0xCED2, 在 0xD4DA), while EUC-KR Hangul fills exactly the leads 0xB0-0xC8 of it, and Korean
 *   separates words with spaces.
 * - Shift_JIS hiragana are 0x82 0x9F-0xF1 and katakana 0x83 0x40-0x96, a corner GB2312 never uses.
 *   EUC-JP kana are 0xA4 and 0xA5 rows, which simplified Chinese text does not use either.
 * - Big5 places a large share of its characters on trails 0x40-0x7E, which GB2312 never does, and
 *   never uses trails 0x80-0xA0 or leads below 0x87, which GBK's traditional characters do.
 */
private class LegacyByteStatistics(
    val highBytes: Int,
    val isolatedHighBytes: Int,
    val highLetters: Int,
    val asciiLetters: Int,
    val pairs: Int,
    val brokenPairs: Int,
    val gb2312Pairs: Int,
    val hangulRowPairs: Int,
    val spacedPairs: Int,
    val shiftJisKanaPairs: Int,
    val eucJpKanaPairs: Int,
    val lowTrailPairs: Int,
    val nonBig5Pairs: Int,
) {
    /** High bytes that stand alone among ASCII, or that break the double-byte pattern. */
    val singleByte: Boolean
        get() =
            isolatedHighBytes * 2 > highBytes ||
                brokenPairs * 100 > (pairs + brokenPairs) * MAX_BROKEN_PAIR_PERCENT

    /** Most letters are high bytes: Cyrillic words, rather than Western ones with a few accents. */
    val cyrillic: Boolean get() = highLetters * 2 > asciiLetters

    companion object {
        fun of(data: ByteArray): LegacyByteStatistics {
            var highBytes = 0
            var isolated = 0
            var highLetters = 0
            var asciiLetters = 0
            for (index in data.indices) {
                val byte = data.unsigned(index)
                if (byte < 0x80) {
                    if (byte in 0x41..0x5A || byte in 0x61..0x7A) asciiLetters++
                    continue
                }
                highBytes++
                if (byte >= 0xC0) highLetters++
                val before = index == 0 || data.unsigned(index - 1) < 0x80
                val after = index == data.lastIndex || data.unsigned(index + 1) < 0x80
                if (before && after) isolated++
            }
            var pairs = 0
            var broken = 0
            var gb2312 = 0
            var hangulRows = 0
            var spaced = 0
            var shiftJisKana = 0
            var eucJpKana = 0
            var lowTrail = 0
            var nonBig5 = 0
            var index = 0
            while (index < data.size - 1) {
                val lead = data.unsigned(index)
                if (lead !in 0x81..0xFE) {
                    index++
                    continue
                }
                val trail = data.unsigned(index + 1)
                if (trail in 0x30..0x39 && data.isGb18030FourByteTail(index + 2)) {
                    pairs++
                    index += 4
                    continue
                }
                if (trail < 0x40 || trail == 0x7F || trail == 0xFF) {
                    broken++
                    index++
                    continue
                }
                pairs++
                if (lead in 0xB0..0xF7 && trail in 0xA1..0xFE) {
                    gb2312++
                    if (lead <= 0xC8) hangulRows++
                    if (index + 2 < data.size && data[index + 2] == SPACE) spaced++
                }
                if ((lead == 0x82 && trail in 0x9F..0xF1) || (lead == 0x83 && trail in 0x40..0x96)) shiftJisKana++
                if ((lead == 0xA4 || lead == 0xA5) && trail in 0xA1..0xF6) eucJpKana++
                if (trail in 0x40..0x7E) lowTrail++
                if (lead <= 0x86 || trail in 0x80..0xA0) nonBig5++
                index += 2
            }
            return LegacyByteStatistics(
                highBytes = highBytes,
                isolatedHighBytes = isolated,
                highLetters = highLetters,
                asciiLetters = asciiLetters,
                pairs = pairs,
                brokenPairs = broken,
                gb2312Pairs = gb2312,
                hangulRowPairs = hangulRows,
                spacedPairs = spaced,
                shiftJisKanaPairs = shiftJisKana,
                eucJpKanaPairs = eucJpKana,
                lowTrailPairs = lowTrail,
                nonBig5Pairs = nonBig5,
            )
        }
    }
}

private fun ByteArray.isGb18030FourByteTail(index: Int): Boolean =
    index + 1 < size && unsigned(index) in 0x81..0xFE && unsigned(index + 1) in 0x30..0x39

private fun byteOrderMark(data: ByteArray): YSubtitleCharset? =
    when {
        data.startsWith(0xEF, 0xBB, 0xBF) -> YSubtitleCharset.Utf8
        data.startsWith(0xFF, 0xFE) -> YSubtitleCharset.Utf16Le
        data.startsWith(0xFE, 0xFF) -> YSubtitleCharset.Utf16Be
        else -> null
    }

/** UTF-16 without a mark: the high byte of every ASCII character, which cues are full of, is zero. */
private fun bomlessUtf16(data: ByteArray): YSubtitleCharset? {
    val sample = minOf(data.size, UTF16_SAMPLE_BYTES) / 2 * 2
    if (sample < MIN_UTF16_SAMPLE_BYTES) return null
    var evenZeros = 0
    var oddZeros = 0
    for (index in 0 until sample) {
        if (data[index] != ZERO) continue
        if (index % 2 == 0) evenZeros++ else oddZeros++
    }
    val units = sample / 2
    return when {
        oddZeros * 100 >= units * UTF16_ZERO_PERCENT && evenZeros * 100 < units * UTF16_STRAY_ZERO_PERCENT ->
            YSubtitleCharset.Utf16Le
        evenZeros * 100 >= units * UTF16_ZERO_PERCENT && oddZeros * 100 < units * UTF16_STRAY_ZERO_PERCENT ->
            YSubtitleCharset.Utf16Be
        else -> null
    }
}

/**
 * UTF-8, allowing a sequence cut off at the end and the odd stray byte (a pasted Windows quote).
 * Legacy double-byte text forms a valid sequence only by chance, for about one pair in ten.
 */
private fun looksLikeUtf8(data: ByteArray): Boolean {
    var sequences = 0
    var invalid = 0
    var index = 0
    while (index < data.size) {
        val first = data.unsigned(index)
        val length =
            when (first) {
                in 0x00..0x7F -> 1
                in 0xC2..0xDF -> 2
                in 0xE0..0xEF -> 3
                in 0xF0..0xF4 -> 4
                else -> 0
            }
        when {
            length == 1 -> index++
            length == 0 -> {
                invalid++
                index++
            }
            index + length > data.size -> {
                if ((index + 1 until data.size).any { !data.isContinuation(it) }) invalid++
                break
            }
            data.isUtf8Sequence(index, length) -> {
                sequences++
                index += length
            }
            else -> {
                invalid++
                index++
            }
        }
    }
    return invalid == 0 || invalid * UTF8_SEQUENCES_PER_STRAY_BYTE <= sequences
}

/** No overlong forms, surrogates or values past U+10FFFF. */
private fun ByteArray.isUtf8Sequence(
    index: Int,
    length: Int,
): Boolean {
    if ((1 until length).any { !isContinuation(index + it) }) return false
    val second = unsigned(index + 1)
    return when (unsigned(index)) {
        0xE0 -> second >= 0xA0
        0xED -> second <= 0x9F
        0xF0 -> second >= 0x90
        0xF4 -> second <= 0x8F
        else -> true
    }
}

private fun ByteArray.isContinuation(index: Int): Boolean = unsigned(index) and 0xC0 == 0x80

private fun ByteArray.decodeUtf16(littleEndian: Boolean): String {
    val chars = CharArray(size / 2)
    for (index in chars.indices) {
        val first = unsigned(index * 2)
        val second = unsigned(index * 2 + 1)
        chars[index] = if (littleEndian) ((second shl 8) or first).toChar() else ((first shl 8) or second).toChar()
    }
    return chars.concatToString()
}

private fun ByteArray.unsigned(index: Int): Int = this[index].toInt() and 0xFF

private fun ByteArray.startsWith(vararg prefix: Int): Boolean =
    size >= prefix.size && prefix.indices.all { unsigned(it) == prefix[it] }

private const val SPACE: Byte = 0x20
private const val ZERO: Byte = 0
private const val MIN_PAIRS = 8
private const val MIN_KOREAN_PAIRS = 16
private const val MAX_BROKEN_PAIR_PERCENT = 5
private const val KANA_PERCENT = 20
private const val EUC_JP_KANA_PERCENT = 25
private const val BIG5_LOW_TRAIL_PERCENT = 15
private const val MAX_NON_BIG5_PERCENT = 2
private const val MAX_EUC_JP_LOW_TRAIL_PERCENT = 2
private const val HANGUL_ROW_PERCENT = 95
private const val KOREAN_SPACING_PERCENT = 10
private const val UTF8_SEQUENCES_PER_STRAY_BYTE = 8
private const val UTF16_SAMPLE_BYTES = 4096
private const val MIN_UTF16_SAMPLE_BYTES = 16
private const val UTF16_ZERO_PERCENT = 40
private const val UTF16_STRAY_ZERO_PERCENT = 5
