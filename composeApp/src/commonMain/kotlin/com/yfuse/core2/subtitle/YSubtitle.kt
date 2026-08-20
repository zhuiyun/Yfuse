package com.yfuse.core2.subtitle

enum class YSubtitleFormat {
    Srt,
    WebVtt,
    Ass,
    Ssa,
    Pgs,
    VobSub,
    Tx3g,
    Unknown,
    ;

    val textOverlaySupported: Boolean
        get() = this == Srt || this == WebVtt || this == Ass || this == Ssa || this == Tx3g

    val standaloneTextSupported: Boolean
        get() = this == Srt || this == WebVtt || this == Ass || this == Ssa
}

sealed interface YSubtitlePayload {
    data class TextStyle(
        val bold: Boolean = false,
        val italic: Boolean = false,
        val underline: Boolean = false,
        /** Packed ARGB; null keeps the user-selected subtitle colour. */
        val primaryColorArgb: Int? = null,
        val fontSizePoints: Float? = null,
        /** ASS numpad alignment (1..9). */
        val alignment: Int = 2,
        val outline: Float? = null,
        val shadow: Float? = null,
    ) {
        init {
            require(alignment in 1..9)
            require(fontSizePoints == null || fontSizePoints.isFinite() && fontSizePoints > 0f)
            require(outline == null || outline.isFinite() && outline >= 0f)
            require(shadow == null || shadow.isFinite() && shadow >= 0f)
        }
    }

    data class Text(
        val plainText: String,
        val sourceMarkup: String = plainText,
        val style: TextStyle = TextStyle(),
    ) : YSubtitlePayload

    /** Encoded bitmap/text packet owned by a platform decoder, not by the video renderer. */
    data class Encoded(
        val data: ByteArray,
        val format: YSubtitleFormat,
    ) : YSubtitlePayload

    /** Premultiplied ARGB subtitle rectangle positioned in its authored video canvas. */
    data class BitmapArgb(
        val width: Int,
        val height: Int,
        val x: Int,
        val y: Int,
        val canvasWidth: Int,
        val canvasHeight: Int,
        val pixels: IntArray,
    ) : YSubtitlePayload {
        init {
            require(width > 0 && height > 0)
            require(x >= 0 && y >= 0)
            require(canvasWidth >= x + width && canvasHeight >= y + height)
            require(pixels.size == width * height)
        }
    }
}

data class YSubtitleCue(
    val id: String,
    val startUs: Long,
    val endUs: Long,
    val payload: YSubtitlePayload,
) {
    init {
        require(startUs >= 0L) { "Subtitle start must be non-negative" }
        require(endUs > startUs) { "Subtitle end must be after start" }
    }
}

/** Decodes one subtitle packet whose timing is supplied by its media container. */
object YEmbeddedSubtitleDecoder {
    fun decode(
        data: ByteArray,
        format: YSubtitleFormat,
        startUs: Long,
        durationUs: Long?,
        id: String,
    ): YSubtitleCue? {
        if (!format.textOverlaySupported || data.isEmpty()) return null
        val packetText =
            when (format) {
                YSubtitleFormat.Tx3g -> data.decodeTx3g()
                else -> data.decodeToString()
            }.trimEnd('\u0000', '\r', '\n')
        val markup =
            when (format) {
                YSubtitleFormat.Ass, YSubtitleFormat.Ssa -> packetText.assDialogueText()
                else -> packetText
            }.replace("\\N", "\n").replace("\\n", "\n")
        val plainText =
            markup
                .replace(ASS_OVERRIDE, "")
                .replace(SIMPLE_TAG, "")
                .decodeBasicEntities()
                .trim()
        if (plainText.isEmpty()) return null
        val safeStartUs = startUs.coerceAtLeast(0L)
        val safeDurationUs = durationUs?.takeIf { it > 0L } ?: DEFAULT_PACKET_DURATION_US
        return YSubtitleCue(
            id = id,
            startUs = safeStartUs,
            endUs = safeStartUs + safeDurationUs,
            payload =
                YSubtitlePayload.Text(
                    plainText = plainText,
                    sourceMarkup = markup,
                    style =
                        if (format == YSubtitleFormat.Ass || format == YSubtitleFormat.Ssa) {
                            assTextStyle(markup)
                        } else {
                            YSubtitlePayload.TextStyle()
                        },
                ),
        )
    }
}

/** Immutable cue index used by an independent subtitle overlay above SurfaceView. */
class YSubtitleTimeline(
    cues: List<YSubtitleCue>,
) {
    private val ordered = cues.sortedWith(compareBy(YSubtitleCue::startUs, YSubtitleCue::endUs, YSubtitleCue::id))

    val cues: List<YSubtitleCue> get() = ordered

    fun activeAt(
        playbackPositionUs: Long,
        delayUs: Long = 0L,
    ): List<YSubtitleCue> {
        val subtitlePositionUs = playbackPositionUs - delayUs
        if (subtitlePositionUs < 0L || ordered.isEmpty()) return emptyList()
        val upperBound = ordered.firstIndexAfter(subtitlePositionUs)
        return ordered.subList(0, upperBound).filter { cue -> subtitlePositionUs < cue.endUs }
    }
}

/** Resolves supported text sidecars without trusting an HTTP content type or filename alone. */
fun externalTextSubtitleFormat(
    uri: String,
    mimeType: String? = null,
    contentPrefix: String = "",
): YSubtitleFormat? {
    val declared =
        when (mimeType?.substringBefore(';')?.trim()?.lowercase()) {
            "application/x-subrip", "application/srt", "text/srt" -> YSubtitleFormat.Srt
            "text/vtt" -> YSubtitleFormat.WebVtt
            "text/x-ass", "text/x-ssa", "application/x-ass", "application/x-ssa" -> YSubtitleFormat.Ass
            else -> null
        }
    if (declared != null) return declared

    val path = uri.substringBefore('#').substringBefore('?').lowercase()
    val extension =
        when {
            path.endsWith(".srt") -> YSubtitleFormat.Srt
            path.endsWith(".vtt") -> YSubtitleFormat.WebVtt
            path.endsWith(".ass") -> YSubtitleFormat.Ass
            path.endsWith(".ssa") -> YSubtitleFormat.Ssa
            else -> null
        }
    if (extension != null) return extension

    val prefix = contentPrefix.removePrefix("\uFEFF").trimStart()
    return when {
        prefix.startsWith("WEBVTT", ignoreCase = true) -> YSubtitleFormat.WebVtt
        prefix.startsWith("[Script Info]", ignoreCase = true) ||
            prefix.contains("\n[Events]", ignoreCase = true) -> YSubtitleFormat.Ass
        "-->" in prefix -> YSubtitleFormat.Srt
        else -> null
    }
}

/** Decodes the Unicode encodings routinely used by downloaded subtitle sidecars. */
fun decodeExternalSubtitleText(data: ByteArray): String =
    when {
        data.size >= 2 && data[0] == 0xff.toByte() && data[1] == 0xfe.toByte() ->
            data.decodeUtf16(offset = 2, littleEndian = true)
        data.size >= 2 && data[0] == 0xfe.toByte() && data[1] == 0xff.toByte() ->
            data.decodeUtf16(offset = 2, littleEndian = false)
        else -> data.decodeToString().removePrefix("\uFEFF")
    }

private fun ByteArray.decodeUtf16(
    offset: Int,
    littleEndian: Boolean,
): String {
    val chars = CharArray((size - offset) / 2)
    chars.indices.forEach { index ->
        val first = this[offset + index * 2].toInt() and 0xff
        val second = this[offset + index * 2 + 1].toInt() and 0xff
        chars[index] = if (littleEndian) ((second shl 8) or first).toChar() else ((first shl 8) or second).toChar()
    }
    return chars.concatToString()
}

private fun List<YSubtitleCue>.firstIndexAfter(positionUs: Long): Int {
    var low = 0
    var high = size
    while (low < high) {
        val middle = (low + high) ushr 1
        if (this[middle].startUs <= positionUs) {
            low = middle + 1
        } else {
            high = middle
        }
    }
    return low
}

private fun ByteArray.decodeTx3g(): String {
    if (size < 2) return decodeToString()
    val textSize = ((this[0].toInt() and 0xff) shl 8) or (this[1].toInt() and 0xff)
    if (textSize <= 0 || textSize > size - 2) return decodeToString()
    return copyOfRange(2, 2 + textSize).decodeToString()
}

private fun String.assDialogueText(): String {
    val payload = removePrefix("Dialogue:").trimStart()
    val fields = payload.split(',', limit = ASS_PACKET_FIELD_COUNT)
    return fields.lastOrNull().orEmpty()
}

/** Parses whole-cue ASS overrides without moving video frames through a GPU composition path. */
fun assTextStyle(
    markup: String,
    inherited: YSubtitlePayload.TextStyle = YSubtitlePayload.TextStyle(),
): YSubtitlePayload.TextStyle {
    val commands = ASS_OVERRIDE.findAll(markup).joinToString("\\") { it.value.trim('{', '}') }

    fun flag(name: String): Boolean? =
        Regex("(?:^|\\\\)$name(-?\\d+)", RegexOption.IGNORE_CASE)
            .findAll(commands)
            .lastOrNull()
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
            ?.let { it != 0 }

    fun number(name: String): Float? =
        Regex("(?:^|\\\\)$name([0-9]+(?:\\.[0-9]+)?)", RegexOption.IGNORE_CASE)
            .findAll(commands)
            .lastOrNull()
            ?.groupValues
            ?.get(1)
            ?.toFloatOrNull()
    val alignment =
        Regex("(?:^|\\\\)an([1-9])", RegexOption.IGNORE_CASE)
            .findAll(commands)
            .lastOrNull()
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
            ?: inherited.alignment
    val color =
        Regex("(?:^|\\\\)(?:1?c)&H([0-9A-F]{6,8})&?", RegexOption.IGNORE_CASE)
            .findAll(commands)
            .lastOrNull()
            ?.groupValues
            ?.get(1)
            ?.assColorArgb()
    return YSubtitlePayload.TextStyle(
        bold = flag("b") ?: inherited.bold,
        italic = flag("i") ?: inherited.italic,
        underline = flag("u") ?: inherited.underline,
        primaryColorArgb = color ?: inherited.primaryColorArgb,
        fontSizePoints = number("fs") ?: inherited.fontSizePoints,
        alignment = alignment,
        outline = number("bord") ?: inherited.outline,
        shadow = number("shad") ?: inherited.shadow,
    )
}

internal fun String.assColorArgb(): Int? {
    val raw = toLongOrNull(16) ?: return null
    val red = (raw and 0xff).toInt()
    val green = ((raw ushr 8) and 0xff).toInt()
    val blue = ((raw ushr 16) and 0xff).toInt()
    val assAlpha = if (length >= 8) ((raw ushr 24) and 0xff).toInt() else 0
    val alpha = 0xff - assAlpha
    return (alpha shl 24) or (red shl 16) or (green shl 8) or blue
}

private fun String.decodeBasicEntities(): String =
    replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")
        .replace("&nbsp;", " ")

private val SIMPLE_TAG = Regex("</?[A-Za-z][^>]*>")
private val ASS_OVERRIDE = Regex("\\{[^}]*}")
private const val ASS_PACKET_FIELD_COUNT = 9
private const val DEFAULT_PACKET_DURATION_US = 5_000_000L
