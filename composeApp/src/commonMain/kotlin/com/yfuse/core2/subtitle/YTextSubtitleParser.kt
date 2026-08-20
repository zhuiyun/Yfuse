package com.yfuse.core2.subtitle

object YTextSubtitleParser {
    fun parse(
        data: ByteArray,
        format: YSubtitleFormat,
    ): YSubtitleTimeline = parse(data.decodeToString(), format)

    fun parse(
        text: String,
        format: YSubtitleFormat,
    ): YSubtitleTimeline =
        YSubtitleTimeline(
            when (format) {
                YSubtitleFormat.Srt -> parseSrt(text)
                YSubtitleFormat.WebVtt -> parseWebVtt(text)
                YSubtitleFormat.Ass, YSubtitleFormat.Ssa -> parseAss(text)
                else -> error("$format is not a standalone text subtitle format")
            },
        )
}

private fun parseSrt(text: String): List<YSubtitleCue> =
    normalizedLines(text)
        .splitBlocks()
        .mapIndexedNotNull { index, block ->
            val timingIndex = block.indexOfFirst { line -> SRT_TIMING_SEPARATOR in line }
            if (timingIndex < 0) return@mapIndexedNotNull null
            val timing = parseTimingLine(block[timingIndex], SRT_TIMING_SEPARATOR) ?: return@mapIndexedNotNull null
            val markup = block.drop(timingIndex + 1).joinToString("\n").trim()
            if (markup.isEmpty()) return@mapIndexedNotNull null
            YSubtitleCue(
                id = block.getOrNull(timingIndex - 1)?.takeIf(String::isNotBlank) ?: "srt-$index",
                startUs = timing.first,
                endUs = timing.second,
                payload = YSubtitlePayload.Text(markup.stripSimpleMarkup(), markup),
            )
        }

private fun parseWebVtt(text: String): List<YSubtitleCue> {
    val lines = normalizedLines(text).dropWhile { line -> line.isBlank() || line.startsWith("WEBVTT") }
    return lines
        .splitBlocks()
        .mapIndexedNotNull { index, block ->
            if (block.firstOrNull()?.startsWith("NOTE") == true) return@mapIndexedNotNull null
            val timingIndex = block.indexOfFirst { line -> WEBVTT_TIMING_SEPARATOR in line }
            if (timingIndex < 0) return@mapIndexedNotNull null
            val timing = parseTimingLine(block[timingIndex], WEBVTT_TIMING_SEPARATOR) ?: return@mapIndexedNotNull null
            val markup = block.drop(timingIndex + 1).joinToString("\n").trim()
            if (markup.isEmpty()) return@mapIndexedNotNull null
            YSubtitleCue(
                id = block.getOrNull(timingIndex - 1)?.takeIf(String::isNotBlank) ?: "vtt-$index",
                startUs = timing.first,
                endUs = timing.second,
                payload = YSubtitlePayload.Text(markup.stripSimpleMarkup(), markup),
            )
        }
}

private fun parseAss(text: String): List<YSubtitleCue> {
    val lines = normalizedLines(text)
    var section = ""
    var fields = DEFAULT_ASS_FIELDS
    var styleFields = DEFAULT_ASS_STYLE_FIELDS
    val styles = mutableMapOf<String, YSubtitlePayload.TextStyle>()
    val cues = mutableListOf<YSubtitleCue>()
    lines.forEach { rawLine ->
        val line = rawLine.trim()
        if (line.startsWith("[")) {
            section = line.lowercase()
            return@forEach
        }
        if (section == "[v4+ styles]" || section == "[v4 styles]") {
            if (line.startsWith("Format:", ignoreCase = true)) {
                styleFields = line.substringAfter(':').split(',').map { it.trim().lowercase() }
            } else if (line.startsWith("Style:", ignoreCase = true)) {
                val values = line.substringAfter(':').split(',', limit = styleFields.size)
                val name =
                    values
                        .valueFor(styleFields, "name")
                        ?.trim()
                        ?.lowercase()
                        .orEmpty()
                if (name.isNotEmpty()) styles[name] = values.toAssStyle(styleFields)
            }
            return@forEach
        }
        if (section != "[events]") return@forEach
        if (line.startsWith("Format:", ignoreCase = true)) {
            fields = line.substringAfter(':').split(',').map { it.trim().lowercase() }
            return@forEach
        }
        if (!line.startsWith("Dialogue:", ignoreCase = true)) return@forEach
        val values = line.substringAfter(':').split(',', limit = fields.size)
        if (values.size != fields.size) return@forEach
        val start = values.valueFor(fields, "start")?.parseSubtitleTimeUs() ?: return@forEach
        val end = values.valueFor(fields, "end")?.parseSubtitleTimeUs() ?: return@forEach
        if (end <= start) return@forEach
        val markup =
            values
                .valueFor(fields, "text")
                .orEmpty()
                .replace("\\N", "\n")
                .replace("\\n", "\n")
        if (markup.isBlank()) return@forEach
        val baseStyle =
            values
                .valueFor(fields, "style")
                ?.trim()
                ?.lowercase()
                ?.let(styles::get)
                ?: YSubtitlePayload.TextStyle()
        cues +=
            YSubtitleCue(
                id = "ass-${cues.size}",
                startUs = start,
                endUs = end,
                payload =
                    YSubtitlePayload.Text(
                        plainText = markup.stripAssOverrides(),
                        sourceMarkup = markup,
                        style = assTextStyle(markup, baseStyle),
                    ),
            )
    }
    return cues
}

private fun List<String>.toAssStyle(fields: List<String>): YSubtitlePayload.TextStyle {
    fun value(name: String): String? = valueFor(fields, name)?.trim()

    fun enabled(name: String): Boolean = value(name)?.toIntOrNull()?.let { it != 0 } ?: false
    return YSubtitlePayload.TextStyle(
        bold = enabled("bold"),
        italic = enabled("italic"),
        underline = enabled("underline"),
        primaryColorArgb = value("primarycolour")?.removePrefix("&H")?.removeSuffix("&")?.assColorArgb(),
        fontSizePoints = value("fontsize")?.toFloatOrNull()?.takeIf { it > 0f },
        alignment = value("alignment")?.toIntOrNull()?.takeIf { it in 1..9 } ?: 2,
        outline = value("outline")?.toFloatOrNull()?.takeIf { it >= 0f },
        shadow = value("shadow")?.toFloatOrNull()?.takeIf { it >= 0f },
    )
}

private fun parseTimingLine(
    line: String,
    separator: String,
): Pair<Long, Long>? {
    val start = line.substringBefore(separator).trim().parseSubtitleTimeUs() ?: return null
    val endToken = line.substringAfter(separator).trim().substringBefore(' ')
    val end = endToken.parseSubtitleTimeUs() ?: return null
    return if (end > start) start to end else null
}

private fun String.parseSubtitleTimeUs(): Long? {
    val normalized = trim().replace(',', '.')
    val parts = normalized.split(':')
    if (parts.size !in 2..3) return null
    val hours = if (parts.size == 3) parts[0].toLongOrNull() ?: return null else 0L
    val minutes = parts[parts.size - 2].toLongOrNull() ?: return null
    val secondParts = parts.last().split('.', limit = 2)
    val seconds = secondParts[0].toLongOrNull() ?: return null
    if (minutes !in 0..59 || seconds !in 0..59) return null
    val fraction = secondParts.getOrNull(1).orEmpty()
    if (fraction.any { !it.isDigit() }) return null
    val micros = fraction.take(6).padEnd(6, '0').toLongOrNull() ?: 0L
    return (((hours * 60L + minutes) * 60L + seconds) * MICROS_PER_SECOND) + micros
}

private fun normalizedLines(text: String): List<String> =
    text
        .removePrefix("\uFEFF")
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .split('\n')

private fun List<String>.splitBlocks(): List<List<String>> {
    val blocks = mutableListOf<MutableList<String>>()
    var current = mutableListOf<String>()
    forEach { line ->
        if (line.isBlank()) {
            if (current.isNotEmpty()) {
                blocks += current
                current = mutableListOf()
            }
        } else {
            current += line
        }
    }
    if (current.isNotEmpty()) blocks += current
    return blocks
}

private fun List<String>.valueFor(
    fields: List<String>,
    name: String,
): String? = fields.indexOf(name).takeIf { it >= 0 }?.let(::get)

private fun String.stripSimpleMarkup(): String = replace(SIMPLE_TAG, "").decodeBasicEntities()

private fun String.stripAssOverrides(): String = replace(ASS_OVERRIDE, "").decodeBasicEntities()

private fun String.decodeBasicEntities(): String =
    replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")
        .replace("&nbsp;", " ")

private val SIMPLE_TAG = Regex("</?[A-Za-z][^>]*>")
private val ASS_OVERRIDE = Regex("\\{[^}]*}")
private val DEFAULT_ASS_FIELDS =
    listOf("layer", "start", "end", "style", "name", "marginl", "marginr", "marginv", "effect", "text")
private val DEFAULT_ASS_STYLE_FIELDS =
    listOf(
        "name",
        "fontname",
        "fontsize",
        "primarycolour",
        "secondarycolour",
        "outlinecolour",
        "backcolour",
        "bold",
        "italic",
        "underline",
        "strikeout",
        "scalex",
        "scaley",
        "spacing",
        "angle",
        "borderstyle",
        "outline",
        "shadow",
        "alignment",
        "marginl",
        "marginr",
        "marginv",
        "encoding",
    )
private const val SRT_TIMING_SEPARATOR = "-->"
private const val WEBVTT_TIMING_SEPARATOR = "-->"
private const val MICROS_PER_SECOND = 1_000_000L
