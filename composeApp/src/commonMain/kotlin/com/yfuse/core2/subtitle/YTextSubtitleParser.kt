package com.yfuse.core2.subtitle

object YTextSubtitleParser {
    fun parse(
        data: ByteArray,
        format: YSubtitleFormat,
    ): YSubtitleTimeline = parse(data.decodeToString(), format)

    fun parse(
        text: String,
        format: YSubtitleFormat,
        ensureActive: () -> Unit = {},
    ): YSubtitleTimeline =
        YSubtitleTimeline(
            when (format) {
                YSubtitleFormat.Srt -> parseSrt(text, ensureActive)
                YSubtitleFormat.WebVtt -> parseWebVtt(text, ensureActive)
                YSubtitleFormat.Ass, YSubtitleFormat.Ssa -> parseAss(text, ensureActive)
                YSubtitleFormat.Smi -> parseSami(text, ensureActive)
                YSubtitleFormat.MicroDvd -> parseMicroDvd(text, ensureActive)
                YSubtitleFormat.Ttml -> parseTtml(text, ensureActive)
                else -> error("$format is not a standalone text subtitle format")
            },
        )
}

private fun parseSrt(
    text: String,
    ensureActive: () -> Unit,
): List<YSubtitleCue> =
    normalizedLines(text, ensureActive)
        .splitBlocks(ensureActive)
        .mapIndexedNotNull { index, block ->
            ensureActive()
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

private fun parseWebVtt(
    text: String,
    ensureActive: () -> Unit,
): List<YSubtitleCue> {
    val lines = normalizedLines(text, ensureActive).dropWhile { line -> line.isBlank() || line.startsWith("WEBVTT") }
    return lines
        .splitBlocks(ensureActive)
        .mapIndexedNotNull { index, block ->
            ensureActive()
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

private fun parseAss(
    text: String,
    ensureActive: () -> Unit,
): List<YSubtitleCue> {
    val lines = normalizedLines(text, ensureActive)
    var section = ""
    var fields = DEFAULT_ASS_FIELDS
    var styleFields = DEFAULT_ASS_STYLE_FIELDS
    val styles = mutableMapOf<String, YSubtitlePayload.TextStyle>()
    val cues = mutableListOf<YSubtitleCue>()
    lines.forEach { rawLine ->
        ensureActive()
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

/**
 * SAMI: every `<SYNC Start=ms>` opens a caption that lasts until the next one, and a caption of
 * only `&nbsp;` ends the one before. A file may carry several languages as paragraph classes
 * (`KRCC`, `ENCC`); the one with the most captions is shown, which is the main track in practice.
 */
private fun parseSami(
    text: String,
    ensureActive: () -> Unit,
): List<YSubtitleCue> {
    ensureActive()
    val body = text.removePrefix("\uFEFF")
    val syncs = SAMI_SYNC.findAll(body).toList()

    data class Caption(
        val startUs: Long,
        val className: String,
        val markup: String,
    )
    val captions =
        syncs.flatMapIndexed { index, sync ->
            ensureActive()
            val startUs = (sync.groupValues[1].toLongOrNull() ?: return@flatMapIndexed emptyList()) * 1_000L
            val content = body.substring(sync.range.last + 1, syncs.getOrNull(index + 1)?.range?.first ?: body.length)
            val paragraphs = SAMI_PARAGRAPH.findAll(content).toList()
            if (paragraphs.isEmpty()) {
                listOf(Caption(startUs, "", content))
            } else {
                paragraphs.mapIndexed { paragraphIndex, paragraph ->
                    val end = paragraphs.getOrNull(paragraphIndex + 1)?.range?.first ?: content.length
                    Caption(
                        startUs,
                        paragraph.groupValues[1].lowercase(),
                        content.substring(paragraph.range.last + 1, end),
                    )
                }
            }
        }
    val mainClass =
        captions
            .filter { it.markup.samiPlainText().isNotBlank() }
            .groupingBy(Caption::className)
            .eachCount()
            .maxByOrNull { it.value }
            ?.key ?: return emptyList()
    val track = captions.filter { it.className == mainClass }.sortedBy(Caption::startUs)
    return track.mapIndexedNotNull { index, caption ->
        val plain = caption.markup.samiPlainText()
        if (plain.isBlank()) return@mapIndexedNotNull null
        val endUs =
            track.drop(index + 1).firstOrNull { it.startUs > caption.startUs }?.startUs
                ?: (caption.startUs + DEFAULT_LAST_CUE_US)
        YSubtitleCue(
            id = "smi-$index",
            startUs = caption.startUs,
            endUs = endUs,
            payload = YSubtitlePayload.Text(plain, plain),
        )
    }
}

private fun String.samiPlainText(): String =
    replace(SAMI_BREAK, "\n")
        .replace(SIMPLE_TAG, "")
        .decodeBasicEntities()
        .lines()
        .joinToString("\n") { it.trim() }
        .trim()

/**
 * MicroDVD: `{start}{end}text` in frame numbers, `|` between lines, `{y:i}`-style codes before the
 * text. A first line of `{1}{1}23.976` states the frame rate; without it 23.976 is assumed, the
 * rate such files were nearly always made for.
 */
private fun parseMicroDvd(
    text: String,
    ensureActive: () -> Unit,
): List<YSubtitleCue> {
    val lines = normalizedLines(text, ensureActive)
    var framesPerSecond = DEFAULT_MICRO_DVD_FPS
    val cues = mutableListOf<YSubtitleCue>()
    lines.forEachIndexed { index, line ->
        ensureActive()
        val match = MICRO_DVD_CUE.matchEntire(line.trim()) ?: return@forEachIndexed
        val startFrame = match.groupValues[1].toLongOrNull() ?: return@forEachIndexed
        val endFrame = match.groupValues[2].toLongOrNull()
        val body = match.groupValues[3]
        if (cues.isEmpty() && startFrame <= 1L && (endFrame ?: 0L) <= 1L) {
            body.trim().toDoubleOrNull()?.takeIf { it in 1.0..240.0 }?.let {
                framesPerSecond = it
                return@forEachIndexed
            }
        }
        val plain =
            body
                .replace(MICRO_DVD_CODE, "")
                .split('|')
                .joinToString("\n") { it.trim() }
                .trim()
        if (plain.isEmpty()) return@forEachIndexed
        val startUs = (startFrame * 1_000_000.0 / framesPerSecond).toLong()
        val endUs =
            endFrame?.let { (it * 1_000_000.0 / framesPerSecond).toLong() }?.takeIf { it > startUs }
                ?: (startUs + DEFAULT_LAST_CUE_US)
        cues +=
            YSubtitleCue(
                id = "microdvd-$index",
                startUs = startUs,
                endUs = endUs,
                payload = YSubtitlePayload.Text(plain, plain),
            )
    }
    return cues
}

/**
 * TTML / DFXP paragraphs: `begin` with `end` or `dur`, as clock time (`00:01:02.500`, or with frames
 * `00:01:02:12`) or offset time (`62.5s`, `1500ms`, `90f`, `5000t`). Spans are flattened and `<br/>`
 * breaks lines; styling and nested time containers are not applied.
 */
private fun parseTtml(
    text: String,
    ensureActive: () -> Unit,
): List<YSubtitleCue> {
    ensureActive()
    val document = text.removePrefix("\uFEFF")
    val root =
        TTML_ROOT
            .find(document)
            ?.groupValues
            ?.get(1)
            .orEmpty()
    val frameRate =
        TTML_FRAME_RATE
            .find(root)
            ?.groupValues
            ?.get(1)
            ?.toDoubleOrNull()
            ?.takeIf { it > 0.0 }
            ?: DEFAULT_TTML_FRAME_RATE
    val tickRate =
        TTML_TICK_RATE
            .find(root)
            ?.groupValues
            ?.get(1)
            ?.toDoubleOrNull()
            ?.takeIf { it > 0.0 } ?: 1.0
    return TTML_PARAGRAPH
        .findAll(document)
        .mapIndexedNotNull { index, paragraph ->
            ensureActive()
            val attributes = paragraph.groupValues[1]
            val startUs =
                TTML_BEGIN
                    .find(attributes)
                    ?.groupValues
                    ?.get(1)
                    ?.ttmlTimeUs(frameRate, tickRate)
                    ?: return@mapIndexedNotNull null
            val endUs =
                TTML_END
                    .find(attributes)
                    ?.groupValues
                    ?.get(1)
                    ?.ttmlTimeUs(frameRate, tickRate)
                    ?: TTML_DURATION.find(attributes)?.groupValues?.get(1)?.ttmlTimeUs(frameRate, tickRate)?.let {
                        startUs +
                            it
                    }
                    ?: return@mapIndexedNotNull null
            if (endUs <= startUs) return@mapIndexedNotNull null
            val plain =
                paragraph.groupValues[2]
                    .replace(TTML_BREAK, "\n")
                    .replace(SIMPLE_TAG, "")
                    .decodeBasicEntities()
                    .replace("&quot;", "\"")
                    .replace("&apos;", "'")
                    .lines()
                    .joinToString("\n") { it.trim() }
                    .trim()
            if (plain.isEmpty()) return@mapIndexedNotNull null
            YSubtitleCue(
                id = "ttml-$index",
                startUs = startUs,
                endUs = endUs,
                payload = YSubtitlePayload.Text(plain, plain),
            )
        }.toList()
}

private fun String.ttmlTimeUs(
    frameRate: Double,
    tickRate: Double,
): Long? {
    val value = trim()
    TTML_OFFSET.matchEntire(value)?.let { match ->
        val amount = match.groupValues[1].toDoubleOrNull() ?: return null
        val seconds =
            when (match.groupValues[2]) {
                "h" -> amount * 3_600.0
                "m" -> amount * 60.0
                "s" -> amount
                "ms" -> amount / 1_000.0
                "f" -> amount / frameRate
                "t" -> amount / tickRate
                else -> return null
            }
        return (seconds * 1_000_000.0).toLong().takeIf { it >= 0L }
    }
    val parts = value.split(':')
    if (parts.size == 4) {
        val (hours, minutes, seconds) = parts.take(3).map { it.toLongOrNull() ?: return null }
        val frames = parts[3].toDoubleOrNull() ?: return null
        return ((hours * 3_600L + minutes * 60L + seconds) * 1_000_000L) + (frames / frameRate * 1_000_000.0).toLong()
    }
    return value.parseSubtitleTimeUs()
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

private fun normalizedLines(
    text: String,
    ensureActive: () -> Unit,
): List<String> {
    ensureActive()
    return text
        .removePrefix("\uFEFF")
        .lineSequence()
        .map { line ->
            ensureActive()
            line
        }.toList()
}

private fun List<String>.splitBlocks(ensureActive: () -> Unit): List<List<String>> {
    val blocks = mutableListOf<MutableList<String>>()
    var current = mutableListOf<String>()
    forEach { line ->
        ensureActive()
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
private val SAMI_SYNC = Regex("<sync\\s[^>]*?start\\s*=\\s*[\"']?(\\d+)[^>]*>", RegexOption.IGNORE_CASE)
private val SAMI_PARAGRAPH =
    Regex("<p(?:\\s[^>]*?class\\s*=\\s*[\"']?([A-Za-z0-9_-]+))?[^>]*>", RegexOption.IGNORE_CASE)
private val SAMI_BREAK = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
private val MICRO_DVD_CUE = Regex("\\{(\\d+)\\}\\{(\\d*)\\}(.*)")
private val MICRO_DVD_CODE = Regex("\\{[^}]*\\}")
private val TTML_ROOT = Regex("<tt(?::tt)?\\b([^>]*)>", RegexOption.IGNORE_CASE)
private val TTML_FRAME_RATE = Regex("frameRate\\s*=\\s*[\"']([0-9.]+)[\"']")
private val TTML_TICK_RATE = Regex("tickRate\\s*=\\s*[\"']([0-9.]+)[\"']")
private val TTML_PARAGRAPH = Regex("<(?:tt:)?p\\b([^>]*)>([\\s\\S]*?)</(?:tt:)?p>", RegexOption.IGNORE_CASE)
private val TTML_BEGIN = Regex("\\bbegin\\s*=\\s*[\"']([^\"']+)[\"']")
private val TTML_END = Regex("\\bend\\s*=\\s*[\"']([^\"']+)[\"']")
private val TTML_DURATION = Regex("\\bdur\\s*=\\s*[\"']([^\"']+)[\"']")
private val TTML_OFFSET = Regex("([0-9]+(?:\\.[0-9]+)?)(h|ms|m|s|f|t)")
private val TTML_BREAK = Regex("<(?:tt:)?br\\s*/?>", RegexOption.IGNORE_CASE)
private const val DEFAULT_MICRO_DVD_FPS = 23.976
private const val DEFAULT_TTML_FRAME_RATE = 30.0
private const val DEFAULT_LAST_CUE_US = 4_000_000L
private val ASS_OVERRIDE = Regex("\\{[^}]*\\}")
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
