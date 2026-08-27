package com.yfuse.core2.adaptive

import kotlin.math.roundToLong

/** Strict, allocation-bounded parser for the HLS tags YCore executes itself. */
fun parseYHlsPlaylist(
    text: String,
    baseUri: String,
): YHlsPlaylist {
    val lines =
        text
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .take(MAX_HLS_LINES + 1)
            .toList()
    require(lines.size <= MAX_HLS_LINES) { "HLS playlist exceeds the line limit" }
    require(lines.firstOrNull() == HLS_HEADER) { "HLS playlist is missing #EXTM3U" }
    return if (lines.any { it.startsWith(STREAM_INFO_TAG) }) {
        parseMasterPlaylist(lines, baseUri)
    } else {
        parseMediaPlaylist(lines, baseUri)
    }
}

private fun parseMasterPlaylist(
    lines: List<String>,
    baseUri: String,
): YHlsPlaylist.Master {
    val variants = mutableListOf<YAdaptiveVariant>()
    var index = 0
    while (index < lines.size) {
        val line = lines[index]
        if (!line.startsWith(STREAM_INFO_TAG)) {
            index++
            continue
        }
        val attributes = parseHlsAttributes(line.substringAfter(':', ""))
        val bandwidth =
            attributes["BANDWIDTH"]?.toLongOrNull()?.takeIf { it > 0L }
                ?: error("HLS variant is missing BANDWIDTH")
        var uriIndex = index + 1
        while (uriIndex < lines.size && lines[uriIndex].startsWith('#')) uriIndex++
        val uriLine = lines.getOrNull(uriIndex) ?: error("HLS variant is missing its URI")
        val resolution = attributes["RESOLUTION"]?.parseResolution()
        val variantIndex = variants.size
        variants +=
            YAdaptiveVariant(
                id =
                    attributes["NAME"]
                        ?.takeIf(String::isNotBlank)
                        ?: "hls-$variantIndex-$bandwidth",
                uri = resolveAdaptiveUri(baseUri, uriLine),
                bandwidthBitsPerSecond = bandwidth,
                averageBandwidthBitsPerSecond =
                    attributes["AVERAGE-BANDWIDTH"]?.toLongOrNull()?.takeIf { it > 0L },
                width = resolution?.first,
                height = resolution?.second,
                frameRate = attributes["FRAME-RATE"]?.toDoubleOrNull()?.takeIf { it > 0.0 },
                codecs =
                    attributes["CODECS"]
                        ?.split(',')
                        ?.map(String::trim)
                        ?.filter(String::isNotEmpty)
                        .orEmpty(),
            )
        index = uriIndex + 1
    }
    return YHlsPlaylist.Master(variants)
}

private fun parseMediaPlaylist(
    lines: List<String>,
    baseUri: String,
): YHlsPlaylist.Media {
    var mediaSequence = 0L
    var targetDurationUs: Long? = null
    var pendingDurationUs: Long? = null
    var pendingRange: YAdaptiveByteRange? = null
    var nextRangeOffset = 0L
    var initialization: YAdaptiveInitializationSegment? = null
    var encryption: YAdaptiveEncryption? = null
    var discontinuity = false
    var startUs = 0L
    val segments = mutableListOf<YAdaptiveSegment>()

    lines.drop(1).forEach { line ->
        when {
            line.startsWith(MEDIA_SEQUENCE_TAG) ->
                mediaSequence =
                    line.substringAfter(':').toLongOrNull()?.takeIf { it >= 0L }
                        ?: error("Invalid HLS media sequence")
            line.startsWith(TARGET_DURATION_TAG) ->
                targetDurationUs = line.substringAfter(':').secondsToUs("target duration")
            line.startsWith(EXTINF_TAG) ->
                pendingDurationUs =
                    line.substringAfter(':').substringBefore(',').secondsToUs("segment duration")
            line.startsWith(BYTE_RANGE_TAG) -> {
                pendingRange = line.substringAfter(':').parseByteRange(nextRangeOffset)
                nextRangeOffset =
                    requireNotNull(pendingRange).offset.orZero() + requireNotNull(pendingRange).length
            }
            line.startsWith(MAP_TAG) -> {
                val attributes = parseHlsAttributes(line.substringAfter(':', ""))
                val uri = attributes["URI"] ?: error("HLS map is missing URI")
                initialization =
                    YAdaptiveInitializationSegment(
                        uri = resolveAdaptiveUri(baseUri, uri),
                        byteRange = attributes["BYTERANGE"]?.parseByteRange(defaultOffset = 0L),
                    )
            }
            line.startsWith(KEY_TAG) -> {
                val attributes = parseHlsAttributes(line.substringAfter(':', ""))
                encryption = attributes.toAdaptiveEncryption(baseUri)
            }
            line == DISCONTINUITY_TAG -> discontinuity = true
            !line.startsWith('#') -> {
                val durationUs = pendingDurationUs ?: error("HLS segment is missing EXTINF")
                segments +=
                    YAdaptiveSegment(
                        sequence = mediaSequence + segments.size,
                        uri = resolveAdaptiveUri(baseUri, line),
                        startTimeUs = startUs,
                        durationUs = durationUs,
                        byteRange = pendingRange,
                        initialization = initialization,
                        encryption = encryption,
                        discontinuity = discontinuity,
                    )
                startUs += durationUs
                pendingDurationUs = null
                pendingRange = null
                discontinuity = false
            }
        }
    }
    require(pendingDurationUs == null) { "HLS playlist ends before the segment URI" }
    return YHlsPlaylist.Media(
        isLive = END_LIST_TAG !in lines,
        mediaSequence = mediaSequence,
        targetDurationUs = targetDurationUs,
        segments = segments,
    )
}

internal fun parseHlsAttributes(source: String): Map<String, String> {
    val result = linkedMapOf<String, String>()
    var index = 0
    while (index < source.length) {
        while (index < source.length && (source[index] == ',' || source[index].isWhitespace())) index++
        if (index >= source.length) break
        val equals = source.indexOf('=', startIndex = index)
        require(equals > index) { "Invalid HLS attribute list" }
        val name = source.substring(index, equals).trim().uppercase()
        index = equals + 1
        val value =
            if (index < source.length && source[index] == '"') {
                val endQuote = source.indexOf('"', startIndex = index + 1)
                require(endQuote >= 0) { "Unterminated HLS quoted attribute" }
                source.substring(index + 1, endQuote).also { index = endQuote + 1 }
            } else {
                val comma = source.indexOf(',', startIndex = index).takeIf { it >= 0 } ?: source.length
                source.substring(index, comma).trim().also { index = comma }
            }
        require(name.isNotEmpty() && name !in result) { "Duplicate HLS attribute $name" }
        result[name] = value
    }
    return result
}

internal fun resolveAdaptiveUri(
    baseUri: String,
    reference: String,
): String {
    val target = reference.trim()
    require(target.isNotEmpty())
    if (URI_SCHEME.matches(target.substringBefore('/'))) return target
    val schemeEnd = baseUri.indexOf("://")
    if (target.startsWith("//")) {
        require(schemeEnd > 0) { "Scheme-relative URI requires an absolute base" }
        return baseUri.substring(0, schemeEnd) + ":" + target
    }
    if (schemeEnd < 0) {
        return normalizeRelativePath(baseUri.substringBeforeLast('/', "") + "/" + target)
    }
    val authorityEnd = baseUri.indexOf('/', startIndex = schemeEnd + 3).takeIf { it >= 0 } ?: baseUri.length
    val origin = baseUri.substring(0, authorityEnd)
    if (target.startsWith('/')) return origin + normalizeRelativePath(target)
    val basePath =
        baseUri
            .substring(authorityEnd)
            .substringBefore('?')
            .substringBefore('#')
            .substringBeforeLast('/', "")
    return origin + normalizeRelativePath("$basePath/$target")
}

private fun normalizeRelativePath(pathAndQuery: String): String {
    val suffixIndex = pathAndQuery.indexOfAny(charArrayOf('?', '#')).takeIf { it >= 0 }
    val path = suffixIndex?.let { pathAndQuery.substring(0, it) } ?: pathAndQuery
    val suffix = suffixIndex?.let { pathAndQuery.substring(it) }.orEmpty()
    val absolute = path.startsWith('/')
    val trailingSlash = path.endsWith('/')
    val stack = mutableListOf<String>()
    path.split('/').forEach { part ->
        when (part) {
            "", "." -> Unit
            ".." -> if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
            else -> stack += part
        }
    }
    return (if (absolute) "/" else "") +
        stack.joinToString("/") +
        (if (trailingSlash && stack.isNotEmpty()) "/" else "") +
        suffix
}

private fun String.parseResolution(): Pair<Int, Int>? {
    val width = substringBefore('x').toIntOrNull()
    val height = substringAfter('x', "").toIntOrNull()
    return if (width != null && height != null && width > 0 && height > 0) width to height else null
}

private fun String.parseByteRange(defaultOffset: Long): YAdaptiveByteRange {
    val length = substringBefore('@').toLongOrNull()?.takeIf { it > 0L } ?: error("Invalid HLS byte range")
    val offset =
        substringAfter('@', "")
            .takeIf(String::isNotEmpty)
            ?.toLongOrNull()
            ?.takeIf { it >= 0L }
            ?: defaultOffset
    return YAdaptiveByteRange(length = length, offset = offset)
}

private fun Map<String, String>.toAdaptiveEncryption(baseUri: String): YAdaptiveEncryption? {
    val method = get("METHOD")?.uppercase() ?: error("HLS key is missing METHOD")
    if (method == "NONE") return null
    return YAdaptiveEncryption(
        method =
            when (method) {
                "AES-128" -> YAdaptiveEncryptionMethod.Aes128
                "SAMPLE-AES", "SAMPLE-AES-CTR" -> YAdaptiveEncryptionMethod.SampleAes
                else -> YAdaptiveEncryptionMethod.Other
            },
        keyUri = get("URI")?.let { resolveAdaptiveUri(baseUri, it) },
        initializationVector = get("IV"),
        keyFormat = get("KEYFORMAT"),
        keyFormatVersions = get("KEYFORMATVERSIONS"),
    )
}

private fun String.secondsToUs(label: String): Long {
    val seconds = toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 } ?: error("Invalid HLS $label")
    return (seconds * MICROS_PER_SECOND).roundToLong().coerceAtLeast(1L)
}

private fun Long?.orZero(): Long = this ?: 0L

private const val MAX_HLS_LINES = 100_000
private const val MICROS_PER_SECOND = 1_000_000.0
private const val HLS_HEADER = "#EXTM3U"
private const val STREAM_INFO_TAG = "#EXT-X-STREAM-INF:"
private const val MEDIA_SEQUENCE_TAG = "#EXT-X-MEDIA-SEQUENCE:"
private const val TARGET_DURATION_TAG = "#EXT-X-TARGETDURATION:"
private const val EXTINF_TAG = "#EXTINF:"
private const val BYTE_RANGE_TAG = "#EXT-X-BYTERANGE:"
private const val MAP_TAG = "#EXT-X-MAP:"
private const val KEY_TAG = "#EXT-X-KEY:"
private const val DISCONTINUITY_TAG = "#EXT-X-DISCONTINUITY"
private const val END_LIST_TAG = "#EXT-X-ENDLIST"
private val URI_SCHEME = Regex("[A-Za-z][A-Za-z0-9+.-]*:")
