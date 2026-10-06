package com.yfuse.core2.subtitle

import kotlin.math.roundToLong

/** TTML media-time containers, namespace URIs and timed spans; unsupported clock bases fail explicitly. */
internal fun parseYTextTtml(
    text: String,
    ensureActive: () -> Unit,
): List<YSubtitleCue> {
    val root = YSubtitleXml(text, ensureActive).read()
    require(root.name.local == "tt" && root.name.namespace in TTML_NAMESPACES) { "Not a TTML document" }
    return TtmlDocument(root, ensureActive).cues()
}

private class TtmlDocument(
    private val root: YXmlContent.Element,
    private val ensureActive: () -> Unit,
) {
    private val timing = TtmlTiming(root)
    private val result = mutableListOf<YSubtitleCue>()
    private var outputChars = 0L

    private data class Timed(
        val element: YXmlContent.Element,
        val start: Long,
        val end: Long?,
        val preserve: Boolean,
        val children: List<Pair<YXmlContent, Timed?>>,
    )

    private data class Piece(
        val start: Long,
        val end: Long,
        val text: String,
    )

    fun cues(): List<YSubtitleCue> {
        val tree = timed(root, 0L, null, false)
        paragraphs(tree, 0L, tree.end)
        return result
    }

    private fun timed(
        element: YXmlContent.Element,
        reference: Long,
        parentEnd: Long?,
        preserve: Boolean,
    ): Timed {
        ensureActive()
        val start = addTime(reference, element.attribute("begin")?.let(timing::parse) ?: 0L)
        val end =
            minTime(
                parentEnd,
                element.attribute("end")?.let { addTime(reference, timing.parse(it)) },
                element.attribute("dur")?.let { addTime(start, timing.parse(it)) },
            )
        val space = element.attribute("space", XML_NAMESPACE)
        require(space == null || space == "default" || space == "preserve") { "Invalid xml:space" }
        val preserveSpace = if (space == null) preserve else space == "preserve"
        val container = element.attribute("timeContainer") ?: "par"
        require(container == "par" || container == "seq") { "Unsupported TTML time container" }
        var cursor: Long? = start
        val children =
            element.content.mapNotNull { child ->
                ensureActive()
                when (child) {
                    is YXmlContent.Text -> child to null
                    is YXmlContent.Element -> {
                        if (child.name.namespace !in TTML_NAMESPACES ||
                            child.name.local !in TIMED_ELEMENTS
                        ) {
                            return@mapNotNull null
                        }
                        val referenceBegin =
                            if (container ==
                                "seq"
                            ) {
                                requireNotNull(cursor) { "Indefinite TTML sequential child" }
                            } else {
                                start
                            }
                        val nested = timed(child, referenceBegin, end, preserveSpace)
                        if (container == "seq") cursor = nested.end
                        child to nested
                    }
                }
            }
        val nested = children.mapNotNull { it.second }
        val hasText = children.any { (it.first as? YXmlContent.Text)?.value?.isNotBlank() == true }
        val implicitEnd =
            if (!hasText &&
                nested.isNotEmpty() &&
                nested.all { it.end != null }
            ) {
                nested.maxOf { checkNotNull(it.end) }
            } else {
                null
            }
        return Timed(element, start, end ?: implicitEnd, preserveSpace, children)
    }

    private fun paragraphs(
        node: Timed,
        parentStart: Long,
        parentEnd: Long?,
    ) {
        ensureActive()
        val start = maxOf(parentStart, node.start)
        val end = minTime(parentEnd, node.end)
        if (end != null && end <= start) return
        if (node.element.name.local == "p") {
            if (end == null) return // No finite media interval to display.
            val pieces = mutableListOf<Piece>()
            collect(node, start, end, pieces)
            render(pieces, node.preserve)
        } else {
            node.children.mapNotNull { it.second }.forEach { paragraphs(it, start, end) }
        }
    }

    private fun collect(
        node: Timed,
        parentStart: Long,
        parentEnd: Long,
        pieces: MutableList<Piece>,
    ) {
        ensureActive()
        val start = maxOf(parentStart, node.start)
        val end = minOf(parentEnd, node.end ?: parentEnd)
        if (end <= start) return
        if (node.element.name.local == "br") {
            pieces += Piece(start, end, "\n")
        } else {
            for ((child, nested) in node.children) {
                if (nested != null) {
                    collect(nested, start, end, pieces)
                } else if (child is YXmlContent.Text) {
                    val text = if (node.preserve) child.value else child.value.replace(XML_SPACE, " ")
                    pieces += Piece(start, end, text)
                }
                require(pieces.size <= MAX_PARAGRAPH_PIECES) { "TTML paragraph is too complex" }
            }
        }
    }

    private fun render(
        pieces: List<Piece>,
        preserve: Boolean,
    ) {
        val boundaries = pieces.flatMap { listOf(it.start, it.end) }.distinct().sorted()
        for (index in 0 until boundaries.lastIndex) {
            ensureActive()
            val start = boundaries[index]
            val end = boundaries[index + 1]
            val raw = buildString { pieces.forEach { if (it.start <= start && start < it.end) append(it.text) } }
            val plain =
                if (preserve) {
                    raw
                } else {
                    raw
                        .replace(
                            SPACE_RUN,
                            " ",
                        ).lines()
                        .joinToString("\n") { it.trim() }
                        .trim()
                }
            if (plain.isBlank()) continue
            outputChars += plain.length
            require(
                result.size < MAX_TTML_CUES && outputChars <= MAX_TTML_OUTPUT_CHARS,
            ) { "TTML cue expansion exceeds limits" }
            result += YSubtitleCue("ttml-${result.size}", start, end, YSubtitlePayload.Text(plain, plain))
        }
    }
}

private class TtmlTiming(
    root: YXmlContent.Element,
) {
    private val nominalRate = root.parameter("frameRate")?.positiveNumber() ?: 30.0
    private val subFrameRate = root.parameter("subFrameRate")?.positiveNumber() ?: 1.0
    private val frameRate: Double
    private val tickRate: Double

    init {
        require(root.parameter("timeBase").let { it == null || it == "media" }) { "Only TTML media time is supported" }
        require(
            root.parameter("dropMode").let { it == null || it == "nonDrop" },
        ) { "Drop-frame TTML time is unsupported" }
        val multiplier = root.parameter("frameRateMultiplier")?.trim()?.split(XML_SPACE)
        val ratio =
            if (multiplier == null) {
                1.0
            } else {
                require(multiplier.size == 2) { "Invalid TTML frameRateMultiplier" }
                multiplier[0].positiveNumber() / multiplier[1].positiveNumber()
            }
        frameRate = nominalRate * ratio
        require(frameRate.isFinite() && frameRate > 0.0) { "Invalid TTML frame rate" }
        tickRate = root.parameter("tickRate")?.positiveNumber()
            ?: if (root.parameter("frameRate") == null) 1.0 else frameRate * subFrameRate
    }

    fun parse(source: String): Long {
        val value = source.trim()
        OFFSET_TIME.matchEntire(value)?.let {
            val amount = it.groupValues[1].toDouble()
            val seconds =
                when (it.groupValues[2]) {
                    "h" -> amount * 3_600.0
                    "m" -> amount * 60.0
                    "s" -> amount
                    "ms" -> amount / 1_000.0
                    "f" -> amount / frameRate
                    "t" -> amount / tickRate
                    else -> error("Invalid TTML offset unit")
                }
            return micros(seconds)
        }
        val match = requireNotNull(CLOCK_TIME.matchEntire(value)) { "Unsupported TTML time expression: $value" }
        val minutes = match.groupValues[2].toInt()
        val seconds = match.groupValues[3].toDouble()
        require(minutes < 60 && seconds < 60) { "Invalid TTML clock time" }
        val frames = match.groupValues[4].toDoubleOrNull() ?: 0.0
        val subFrames = match.groupValues[5].toDoubleOrNull() ?: 0.0
        require(frames < nominalRate && subFrames < subFrameRate) { "Invalid TTML frame/subframe count" }
        return micros(
            match.groupValues[1].toDouble() * 3_600 + minutes * 60 + seconds +
                (frames + subFrames / subFrameRate) / frameRate,
        )
    }

    private fun micros(seconds: Double): Long {
        require(
            seconds.isFinite() && seconds >= 0.0 && seconds < Long.MAX_VALUE / 1_000_000.0,
        ) { "TTML time is out of range" }
        return (seconds * 1_000_000.0).roundToLong()
    }
}

private fun YXmlContent.Element.parameter(name: String): String? =
    attribute(name, TTML_PARAMETER_NAMESPACE) ?: attribute(name, "http://www.w3.org/2006/10/ttaf1#parameter")

private fun String.positiveNumber(): Double =
    toDouble().also {
        require(it.isFinite() && it > 0.0) { "Invalid positive TTML parameter" }
    }

private fun addTime(
    first: Long,
    second: Long,
): Long {
    require(first <= Long.MAX_VALUE - second) { "TTML time overflow" }
    return first + second
}

private fun minTime(vararg values: Long?): Long? = values.filterNotNull().minOrNull()

private val TTML_NAMESPACES = setOf("", "http://www.w3.org/ns/ttml", "http://www.w3.org/2006/10/ttaf1")
private const val TTML_PARAMETER_NAMESPACE = "http://www.w3.org/ns/ttml#parameter"
private val TIMED_ELEMENTS = setOf("tt", "body", "div", "p", "span", "br")
private val XML_SPACE = Regex("[ \\t\\r\\n]+")
private val SPACE_RUN = Regex(" +")
private val OFFSET_TIME = Regex("([0-9]+(?:\\.[0-9]+)?)(h|ms|m|s|f|t)")
private val CLOCK_TIME = Regex("([0-9]+):([0-9]{2}):([0-9]{2}(?:\\.[0-9]+)?)(?::([0-9]+)(?:\\.([0-9]+))?)?")
private const val MAX_PARAGRAPH_PIECES = 2_048
private const val MAX_TTML_CUES = 100_000
private const val MAX_TTML_OUTPUT_CHARS = 8 * 1024 * 1024
