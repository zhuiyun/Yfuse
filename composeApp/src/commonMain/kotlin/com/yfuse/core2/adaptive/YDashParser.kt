package com.yfuse.core2.adaptive

import kotlin.math.roundToLong

/** Bounded MPD parser for SegmentTemplate/SegmentTimeline based DASH presentations. */
fun parseYDashManifest(
    xml: String,
    baseUri: String,
): YDashManifest {
    val root = parseXmlTree(xml)
    val mpd = root.children.singleOrNull { it.localName == "mpd" } ?: error("DASH MPD root is missing")
    val mpdBase = mpd.firstChild("baseurl")?.textValue()?.let { resolveAdaptiveUri(baseUri, it) } ?: baseUri
    val live = mpd.attribute("type")?.equals("dynamic", ignoreCase = true) == true
    val periods = mpd.children("period")
    require(periods.isNotEmpty()) { "DASH MPD contains no Period" }
    val representations = mutableListOf<YDashRepresentation>()
    periods.forEach { period ->
        val periodBase = period.firstChild("baseurl")?.textValue()?.let { resolveAdaptiveUri(mpdBase, it) } ?: mpdBase
        period.children("adaptationset").forEach { adaptation ->
            representations += parseAdaptationSet(adaptation, periodBase)
        }
    }
    return YDashManifest(
        isLive = live,
        minimumUpdatePeriodUs = mpd.attribute("minimumupdateperiod")?.parseIsoDurationUs(),
        mediaPresentationDurationUs = mpd.attribute("mediapresentationduration")?.parseIsoDurationUs(),
        representations = representations,
    )
}

private fun parseAdaptationSet(
    adaptation: XmlNode,
    inheritedBaseUri: String,
): List<YDashRepresentation> {
    val adaptationBase =
        adaptation.firstChild("baseurl")?.textValue()?.let { resolveAdaptiveUri(inheritedBaseUri, it) }
            ?: inheritedBaseUri
    val adaptationTemplate = adaptation.firstChild("segmenttemplate")?.toDashSegmentTemplate()
    val adaptationProtection = adaptation.children("contentprotection").map(XmlNode::toContentProtection)
    val adaptationMime = adaptation.attribute("mimetype")
    val adaptationType = adaptation.attribute("contenttype")
    val adaptationLanguage = adaptation.attribute("lang")
    return adaptation.children("representation").map { representation ->
        val id = representation.attribute("id")?.takeIf(String::isNotBlank) ?: error("DASH Representation has no id")
        val bandwidth =
            representation.attribute("bandwidth")?.toLongOrNull()?.takeIf { it > 0L }
                ?: error("DASH Representation $id has no bandwidth")
        val representationBase =
            representation.firstChild("baseurl")?.textValue()?.let { resolveAdaptiveUri(adaptationBase, it) }
                ?: adaptationBase
        val mime = representation.attribute("mimetype") ?: adaptationMime
        val contentType =
            (representation.attribute("contenttype") ?: adaptationType).toDashContentType(mime)
        val template =
            mergeSegmentTemplates(
                parent = adaptationTemplate,
                child = representation.firstChild("segmenttemplate")?.toDashSegmentTemplate(),
            )
        YDashRepresentation(
            id = id,
            baseUri = representationBase,
            bandwidthBitsPerSecond = bandwidth,
            contentType = contentType,
            mimeType = mime,
            codecs =
                (representation.attribute("codecs") ?: adaptation.attribute("codecs"))
                    ?.split(',')
                    ?.map(String::trim)
                    ?.filter(String::isNotEmpty)
                    .orEmpty(),
            width = representation.attribute("width")?.toIntOrNull() ?: adaptation.attribute("width")?.toIntOrNull(),
            height =
                representation.attribute("height")?.toIntOrNull() ?: adaptation.attribute("height")?.toIntOrNull(),
            frameRate =
                (representation.attribute("framerate") ?: adaptation.attribute("framerate"))
                    ?.parseDashFrameRate(),
            audioSamplingRate =
                (representation.attribute("audiosamplingrate") ?: adaptation.attribute("audiosamplingrate"))
                    ?.toIntOrNull(),
            language = representation.attribute("lang") ?: adaptationLanguage,
            segmentTemplate = template,
            contentProtections =
                (adaptationProtection + representation.children("contentprotection").map(XmlNode::toContentProtection))
                    .distinctBy { protection ->
                        listOf(
                            protection.schemeIdUri,
                            protection.defaultKeyId,
                            protection.psshBase64,
                        )
                    },
        )
    }
}

private data class PartialDashSegmentTemplate(
    val initialization: String?,
    val media: String?,
    val timescale: Long?,
    val duration: Long?,
    val startNumber: Long?,
    val timeline: List<YDashTimelineEntry>?,
)

private fun XmlNode.toDashSegmentTemplate(): PartialDashSegmentTemplate =
    PartialDashSegmentTemplate(
        initialization = attribute("initialization"),
        media = attribute("media"),
        timescale = attribute("timescale")?.toLongOrNull(),
        duration = attribute("duration")?.toLongOrNull(),
        startNumber = attribute("startnumber")?.toLongOrNull(),
        timeline =
            firstChild("segmenttimeline")
                ?.children("s")
                ?.map { entry ->
                    YDashTimelineEntry(
                        startTime = entry.attribute("t")?.toLongOrNull(),
                        duration = entry.attribute("d")?.toLongOrNull() ?: error("DASH timeline entry has no duration"),
                        repeat = entry.attribute("r")?.toIntOrNull() ?: 0,
                    )
                },
    )

private fun mergeSegmentTemplates(
    parent: PartialDashSegmentTemplate?,
    child: PartialDashSegmentTemplate?,
): YDashSegmentTemplate? {
    if (parent == null && child == null) return null
    val media = child?.media ?: parent?.media ?: error("DASH SegmentTemplate has no media pattern")
    val timeline = child?.timeline ?: parent?.timeline.orEmpty()
    return YDashSegmentTemplate(
        initialization = child?.initialization ?: parent?.initialization,
        media = media,
        timescale = child?.timescale ?: parent?.timescale ?: 1L,
        duration = child?.duration ?: parent?.duration,
        startNumber = child?.startNumber ?: parent?.startNumber ?: 1L,
        timeline = timeline,
    )
}

private fun XmlNode.toContentProtection(): YDashContentProtection {
    val scheme = attribute("schemeiduri") ?: error("DASH ContentProtection has no schemeIdUri")
    val licenseNode = firstChild("laurl")
    return YDashContentProtection(
        schemeIdUri = scheme,
        value = attribute("value"),
        defaultKeyId = attribute("default_kid") ?: attribute("defaultkid"),
        psshBase64 = firstChild("pssh")?.textValue(),
        licenseUri = licenseNode?.attribute("licenseurl") ?: licenseNode?.textValue(),
    )
}

/** Expands one DASH URL template without accepting arbitrary format expressions. */
fun renderDashTemplate(
    template: String,
    representation: YDashRepresentation,
    number: Long,
    time: Long? = null,
): String {
    require(number >= 0L)
    val escaped = template.replace("\$\$", DASH_DOLLAR_SENTINEL)
    val rendered =
        DASH_TEMPLATE_TOKEN.replace(escaped) { match ->
            val name = match.groupValues[1]
            val format = match.groupValues[2].takeIf(String::isNotEmpty)
            when (name) {
                "RepresentationID" -> representation.id
                "Bandwidth" -> formatDashNumber(representation.bandwidthBitsPerSecond, format)
                "Number" -> formatDashNumber(number, format)
                "Time" ->
                    formatDashNumber(
                        requireNotNull(time) { "DASH Time template requires a timeline value" },
                        format,
                    )
                else -> error("Unsupported DASH template token $name")
            }
        }
    require('$' !in rendered) { "Unresolved DASH template token" }
    return resolveAdaptiveUri(representation.baseUri, rendered.replace(DASH_DOLLAR_SENTINEL, "\$"))
}

data class YDashExpandedSegment(
    val number: Long,
    val time: Long,
    val duration: Long,
)

fun expandDashTimeline(
    template: YDashSegmentTemplate,
    maximumSegments: Int = MAX_DASH_SEGMENTS,
): List<YDashExpandedSegment> {
    require(maximumSegments > 0)
    if (template.timeline.isEmpty()) {
        return emptyList()
    }
    val output = mutableListOf<YDashExpandedSegment>()
    var nextTime = 0L
    var number = template.startNumber
    template.timeline.forEach { entry ->
        require(entry.repeat >= 0) { "Open-ended DASH timeline repeat requires a live window bound" }
        val start = entry.startTime ?: nextTime
        repeat(entry.repeat + 1) { repeatIndex ->
            require(output.size < maximumSegments) { "DASH timeline exceeds the segment limit" }
            val time = start + repeatIndex * entry.duration
            output += YDashExpandedSegment(number = number++, time = time, duration = entry.duration)
            nextTime = time + entry.duration
        }
    }
    return output
}

private fun formatDashNumber(
    value: Long,
    format: String?,
): String {
    if (format == null) return value.toString()
    val width =
        DASH_NUMBER_FORMAT
            .matchEntire(format)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
            ?: error("Unsupported DASH number format $format")
    require(width in 1..20)
    return value.toString().padStart(width, '0')
}

private fun String?.toDashContentType(mimeType: String?): YDashContentType {
    val normalized = this?.lowercase() ?: mimeType?.substringBefore('/')?.lowercase()
    return when (normalized) {
        "video" -> YDashContentType.Video
        "audio" -> YDashContentType.Audio
        "text", "subtitle", "application" -> YDashContentType.Text
        else -> YDashContentType.Unknown
    }
}

private fun String.parseDashFrameRate(): Double? {
    val numerator = substringBefore('/').toDoubleOrNull() ?: return null
    val denominator = substringAfter('/', "1").toDoubleOrNull()?.takeIf { it > 0.0 } ?: return null
    return (numerator / denominator).takeIf { it.isFinite() && it > 0.0 }
}

private fun String.parseIsoDurationUs(): Long {
    val match = ISO_DURATION.matchEntire(trim()) ?: error("Unsupported ISO-8601 duration")
    val days = match.groupValues[1].toDoubleOrNull() ?: 0.0
    val hours = match.groupValues[2].toDoubleOrNull() ?: 0.0
    val minutes = match.groupValues[3].toDoubleOrNull() ?: 0.0
    val seconds = match.groupValues[4].toDoubleOrNull() ?: 0.0
    val totalSeconds = days * 86_400.0 + hours * 3_600.0 + minutes * 60.0 + seconds
    require(totalSeconds.isFinite() && totalSeconds > 0.0)
    return (totalSeconds * 1_000_000.0).roundToLong()
}

private data class XmlNode(
    val name: String,
    val attributes: Map<String, String>,
    val children: MutableList<XmlNode> = mutableListOf(),
    val text: StringBuilder = StringBuilder(),
) {
    val localName: String get() = name.substringAfter(':').lowercase()

    fun attribute(name: String): String? = attributes[name.lowercase()]

    fun children(name: String): List<XmlNode> = children.filter { it.localName == name.lowercase() }

    fun firstChild(name: String): XmlNode? = children.firstOrNull { it.localName == name.lowercase() }

    fun textValue(): String = decodeXmlEntities(text.toString().trim())
}

private fun parseXmlTree(xml: String): XmlNode {
    require(xml.length <= MAX_MPD_CHARS) { "DASH MPD exceeds the size limit" }
    val root = XmlNode("root", emptyMap())
    val stack = mutableListOf(root)
    var cursor = 0
    var nodes = 1
    XML_TOKEN.findAll(xml).forEach { match ->
        if (match.range.first > cursor) stack.last().text.append(xml.substring(cursor, match.range.first))
        val token = match.value
        cursor = match.range.last + 1
        if (token.startsWith("<!--") || token.startsWith("<?") || token.startsWith("<!")) return@forEach
        if (token.startsWith("</")) {
            val closingName = token.substring(2, token.length - 1).trim().substringBefore(' ')
            require(stack.size > 1 && stack.last().name.equals(closingName, ignoreCase = true)) {
                "Malformed DASH XML closing tag"
            }
            stack.removeAt(stack.lastIndex)
            return@forEach
        }
        val selfClosing = token.endsWith("/>")
        val body = token.substring(1, token.length - if (selfClosing) 2 else 1).trim()
        val name = body.substringBefore(' ').trim()
        require(name.isNotEmpty()) { "Malformed DASH XML tag" }
        val node = XmlNode(name = name, attributes = parseXmlAttributes(body.substringAfter(' ', "")))
        stack.last().children += node
        nodes++
        require(nodes <= MAX_XML_NODES) { "DASH MPD exceeds the node limit" }
        if (!selfClosing) {
            require(stack.size < MAX_XML_DEPTH) { "DASH MPD exceeds the nesting limit" }
            stack += node
        }
    }
    if (cursor < xml.length) stack.last().text.append(xml.substring(cursor))
    require(stack.size == 1) { "DASH XML contains unclosed tags" }
    return root
}

private fun parseXmlAttributes(source: String): Map<String, String> {
    val attributes = linkedMapOf<String, String>()
    XML_ATTRIBUTE.findAll(source).forEach { match ->
        val name = match.groupValues[1].substringAfter(':').lowercase()
        val value = match.groupValues[3].ifEmpty { match.groupValues[4] }
        require(name !in attributes) { "Duplicate DASH XML attribute $name" }
        attributes[name] = decodeXmlEntities(value)
    }
    return attributes
}

private fun decodeXmlEntities(value: String): String =
    value
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")

private const val MAX_MPD_CHARS = 8 * 1024 * 1024
private const val MAX_XML_NODES = 100_000
private const val MAX_XML_DEPTH = 64
private const val MAX_DASH_SEGMENTS = 100_000
private const val DASH_DOLLAR_SENTINEL = "\u0000DOLLAR\u0000"
private val XML_TOKEN = Regex("<!--[\\s\\S]*?-->|<[^>]+>")
private val XML_ATTRIBUTE = Regex("([A-Za-z_][A-Za-z0-9_.:-]*)\\s*=\\s*(\"([^\"]*)\"|'([^']*)')")
private val ISO_DURATION =
    Regex(
        "P(?:(\\d+(?:\\.\\d+)?)D)?(?:T(?:(\\d+(?:\\.\\d+)?)H)?(?:(\\d+(?:\\.\\d+)?)M)?(?:(\\d+(?:\\.\\d+)?)S)?)?",
        RegexOption.IGNORE_CASE,
    )
private val DASH_TEMPLATE_TOKEN = Regex("\\$(RepresentationID|Bandwidth|Number|Time)(%0\\d+d)?\\$")
private val DASH_NUMBER_FORMAT = Regex("%0(\\d+)d")
