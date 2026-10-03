package com.yfuse.core.filesource

/** One `<response>` of a PROPFIND answer, with the properties a folder listing needs. */
internal data class WebDavResource(
    /** Decoded segments of the resource's path from the server root. */
    val pathSegments: List<String>,
    val collection: Boolean,
    val contentLength: Long? = null,
    val lastModifiedEpochMs: Long? = null,
)

/** The body was not a multistatus document this parser can read. */
internal class WebDavParseException(
    message: String,
) : Exception(message)

/**
 * Reads a `207 Multi-Status` body into its resources.
 *
 * A small namespace-aware scanner rather than a platform XML parser, so the same code is tested
 * on the JVM and runs everywhere the listing does. Elements are matched by the `DAV:` namespace
 * and local name, never by prefix: servers send `D:`, `d:`, `ns0:`, `lp1:` or a default namespace,
 * and a property another namespace happens to call `href` must not be read as one. DOCTYPE and
 * processing instructions are skipped, and no entity beyond the predefined five and numeric
 * references is ever expanded.
 */
internal fun parseWebDavMultistatus(xml: String): List<WebDavResource> {
    val resources = mutableListOf<WebDavResource>()
    val namespaces = ArrayDeque<Map<String, String>>()
    val elements = ArrayDeque<String>()
    var response: ResponseBuilder? = null
    var propstat: PropstatBuilder? = null
    val text = StringBuilder()
    var sawMultistatus = false

    XmlScanner(xml).scan { event ->
        when (event) {
            is XmlEvent.Start -> {
                val scope = (namespaces.lastOrNull() ?: emptyMap()) + event.namespaceDeclarations()
                namespaces.addLast(scope)
                val name = event.name.resolve(scope)
                elements.addLast(name)
                text.clear()
                when (name) {
                    DAV_MULTISTATUS -> sawMultistatus = true
                    DAV_RESPONSE -> response = ResponseBuilder()
                    DAV_PROPSTAT -> propstat = PropstatBuilder()
                    DAV_COLLECTION ->
                        if (elements.elementAtOrNull(elements.size - 2) == DAV_RESOURCETYPE) {
                            propstat?.collection = true
                        }
                }
                if (event.selfClosing) {
                    elements.removeLast()
                    namespaces.removeLast()
                }
            }
            is XmlEvent.Text -> text.append(event.value)
            is XmlEvent.End -> {
                val name = elements.removeLastOrNull() ?: throw WebDavParseException("Unbalanced end tag")
                namespaces.removeLastOrNull()
                val value = text.toString().trim()
                text.clear()
                val currentResponse = response
                val currentPropstat = propstat
                when {
                    name == DAV_HREF && currentResponse != null && currentPropstat == null ->
                        if (currentResponse.href == null) currentResponse.href = value
                    name == DAV_STATUS && currentPropstat != null -> currentPropstat.status = statusCode(value)
                    name == DAV_STATUS && currentResponse != null -> currentResponse.status = statusCode(value)
                    name == DAV_CONTENT_LENGTH && currentPropstat != null ->
                        currentPropstat.contentLength = value.toLongOrNull()?.takeIf { it >= 0L }
                    name == DAV_LAST_MODIFIED && currentPropstat != null ->
                        currentPropstat.lastModified = parseHttpDate(value)
                    name == DAV_PROPSTAT && currentResponse != null && currentPropstat != null -> {
                        currentResponse.propstats += currentPropstat
                        propstat = null
                    }
                    name == DAV_RESPONSE && currentResponse != null -> {
                        currentResponse.build()?.let(resources::add)
                        response = null
                    }
                }
            }
        }
    }
    if (!sawMultistatus) throw WebDavParseException("Not a multistatus document")
    return resources
}

/**
 * The direct children of the folder at [requestedSegments] among [resources].
 *
 * The folder itself is the response whose path equals the request. A reverse proxy that strips or
 * adds a prefix makes every href differ from what was asked for, so failing an exact match the
 * shallowest response stands in for it; a server that ignored `Depth: 1` and sent whole subtrees
 * is cut back to one level either way.
 */
internal fun webDavChildren(
    requestedSegments: List<String>,
    resources: List<WebDavResource>,
): List<FileSourceEntry> {
    if (resources.isEmpty()) return emptyList()
    val self =
        resources.firstOrNull { it.pathSegments == requestedSegments }
            ?: resources.firstOrNull { it.pathSegments.equalsIgnoringCase(requestedSegments) }
            ?: resources
                .minBy { it.pathSegments.size }
                .takeIf { shallowest -> resources.any { it.pathSegments.size > shallowest.pathSegments.size } }
    val children =
        if (self == null) {
            resources
        } else {
            val parent = self.pathSegments
            resources.filter { resource ->
                resource !== self &&
                    resource.pathSegments.size == parent.size + 1 &&
                    resource.pathSegments.subList(0, parent.size).equalsIgnoringCase(parent)
            }
        }
    return children
        .mapNotNull { resource ->
            val name = resource.pathSegments.lastOrNull()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            FileSourceEntry(
                name = name,
                directory = resource.collection,
                sizeBytes = resource.contentLength.takeUnless { resource.collection },
                modifiedEpochMs = resource.lastModifiedEpochMs,
            )
        }.distinctBy { it.name }
}

/** `/dav/%E5%BD%B1%E8%A7%86/` or `http://nas/dav/影视/` → `["dav", "影视"]`. */
internal fun webDavHrefSegments(href: String): List<String> {
    val withoutOrigin =
        if (href.startsWith("http://", ignoreCase = true) || href.startsWith("https://", ignoreCase = true)) {
            href.substringAfter("://").let { rest -> rest.indexOf('/').let { if (it < 0) "" else rest.substring(it) } }
        } else {
            href
        }
    return withoutOrigin
        .substringBefore('?')
        .substringBefore('#')
        .split('/')
        .filter(String::isNotEmpty)
        .map(::decodePathSegment)
}

private fun List<String>.equalsIgnoringCase(other: List<String>): Boolean =
    size == other.size && indices.all { this[it].equals(other[it], ignoreCase = true) }

/** `HTTP/1.1 404 Not Found` → 404. */
private fun statusCode(line: String): Int? =
    line
        .trim()
        .split(' ')
        .getOrNull(1)
        ?.toIntOrNull()

private class PropstatBuilder {
    var status: Int? = null
    var collection = false
    var contentLength: Long? = null
    var lastModified: Long? = null
}

private class ResponseBuilder {
    var href: String? = null
    var status: Int? = null
    val propstats = mutableListOf<PropstatBuilder>()

    fun build(): WebDavResource? {
        val location = href ?: return null
        if (status != null && status !in 200..299) return null
        // A property is known only from a propstat that answered 2xx; one reported 404 is absent.
        val found = propstats.filter { it.status == null || it.status in 200..299 }
        return WebDavResource(
            pathSegments = webDavHrefSegments(location),
            collection = found.any { it.collection } || location.endsWith('/'),
            contentLength = found.firstNotNullOfOrNull { it.contentLength },
            lastModifiedEpochMs = found.firstNotNullOfOrNull { it.lastModified },
        )
    }
}

private const val DAV_NS = "DAV:"
private const val DAV_MULTISTATUS = "$DAV_NS|multistatus"
private const val DAV_RESPONSE = "$DAV_NS|response"
private const val DAV_HREF = "$DAV_NS|href"
private const val DAV_STATUS = "$DAV_NS|status"
private const val DAV_PROPSTAT = "$DAV_NS|propstat"
private const val DAV_RESOURCETYPE = "$DAV_NS|resourcetype"
private const val DAV_COLLECTION = "$DAV_NS|collection"
private const val DAV_CONTENT_LENGTH = "$DAV_NS|getcontentlength"
private const val DAV_LAST_MODIFIED = "$DAV_NS|getlastmodified"

/** `D:href` under `xmlns:D="DAV:"` → `DAV:|href`. An unbound prefix keeps its own name as namespace. */
private fun String.resolve(scope: Map<String, String>): String {
    val colon = indexOf(':')
    val prefix = if (colon < 0) "" else substring(0, colon)
    val local = if (colon < 0) this else substring(colon + 1)
    val namespace = scope[prefix] ?: prefix
    return "$namespace|$local"
}

internal sealed interface XmlEvent {
    class Start(
        val name: String,
        val attributes: Map<String, String>,
        val selfClosing: Boolean,
    ) : XmlEvent {
        fun namespaceDeclarations(): Map<String, String> =
            attributes
                .filterKeys { it == "xmlns" || it.startsWith("xmlns:") }
                .mapKeys { (key, _) -> if (key == "xmlns") "" else key.removePrefix("xmlns:") }
    }

    class End(
        val name: String,
    ) : XmlEvent

    class Text(
        val value: String,
    ) : XmlEvent
}

/** A forgiving, allocation-light XML tokenizer; see [parseWebDavMultistatus] for what it skips. */
internal class XmlScanner(
    private val input: String,
) {
    private var position = 0

    fun scan(onEvent: (XmlEvent) -> Unit) {
        while (position < input.length) {
            val open = input.indexOf('<', position)
            if (open < 0) {
                emitText(input.substring(position), onEvent)
                return
            }
            if (open > position) emitText(input.substring(position, open), onEvent)
            position = open
            when {
                input.startsWith("<!--", position) -> position = skipPast("-->")
                input.startsWith("<![CDATA[", position) -> {
                    val end = input.indexOf("]]>", position)
                    if (end < 0) throw WebDavParseException("Unterminated CDATA")
                    onEvent(XmlEvent.Text(input.substring(position + CDATA_OPEN_LENGTH, end)))
                    position = end + CDATA_CLOSE_LENGTH
                }
                input.startsWith("<?", position) -> position = skipPast("?>")
                input.startsWith("<!", position) -> position = skipDeclaration()
                input.startsWith("</", position) -> {
                    val end = input.indexOf('>', position)
                    if (end < 0) throw WebDavParseException("Unterminated end tag")
                    onEvent(XmlEvent.End(input.substring(position + 2, end).trim()))
                    position = end + 1
                }
                else -> onEvent(readStartTag())
            }
        }
    }

    private fun emitText(
        raw: String,
        onEvent: (XmlEvent) -> Unit,
    ) {
        if (raw.isNotEmpty()) onEvent(XmlEvent.Text(decodeXmlEntities(raw)))
    }

    private fun skipPast(terminator: String): Int {
        val end = input.indexOf(terminator, position)
        if (end < 0) throw WebDavParseException("Unterminated markup")
        return end + terminator.length
    }

    /** `<!DOCTYPE …>`, including an internal subset in brackets, whose declarations are ignored. */
    private fun skipDeclaration(): Int {
        var depth = 0
        var index = position
        while (index < input.length) {
            when (input[index]) {
                '[' -> depth++
                ']' -> depth--
                '>' -> if (depth <= 0) return index + 1
            }
            index++
        }
        throw WebDavParseException("Unterminated declaration")
    }

    private fun readStartTag(): XmlEvent.Start {
        var index = position + 1
        val nameStart = index
        while (index < input.length && !input[index].isXmlSpace() && input[index] != '>' && input[index] != '/') index++
        val name = input.substring(nameStart, index)
        if (name.isEmpty()) throw WebDavParseException("Empty element name")
        val attributes = linkedMapOf<String, String>()
        while (true) {
            while (index < input.length && input[index].isXmlSpace()) index++
            if (index >= input.length) throw WebDavParseException("Unterminated start tag")
            when (input[index]) {
                '>' -> {
                    position = index + 1
                    return XmlEvent.Start(name, attributes, selfClosing = false)
                }
                '/' -> {
                    if (input.getOrNull(index + 1) != '>') throw WebDavParseException("Malformed empty element")
                    position = index + 2
                    return XmlEvent.Start(name, attributes, selfClosing = true)
                }
            }
            val keyStart = index
            while (index < input.length && input[index] != '=' && !input[index].isXmlSpace() && input[index] != '>') {
                index++
            }
            val key = input.substring(keyStart, index)
            while (index < input.length && input[index].isXmlSpace()) index++
            if (input.getOrNull(index) != '=') {
                // A bare attribute is not XML; keep going rather than lose the whole listing.
                continue
            }
            index++
            while (index < input.length && input[index].isXmlSpace()) index++
            val quote = input.getOrNull(index)
            if (quote != '"' && quote != '\'') throw WebDavParseException("Unquoted attribute")
            val valueEnd = input.indexOf(quote, index + 1)
            if (valueEnd < 0) throw WebDavParseException("Unterminated attribute")
            attributes[key] = decodeXmlEntities(input.substring(index + 1, valueEnd))
            index = valueEnd + 1
        }
    }

    private companion object {
        const val CDATA_OPEN_LENGTH = 9
        const val CDATA_CLOSE_LENGTH = 3
    }
}

private fun Char.isXmlSpace(): Boolean = this == ' ' || this == '\t' || this == '\n' || this == '\r'

/** The five predefined entities and numeric references; anything else is left as written. */
internal fun decodeXmlEntities(raw: String): String {
    if ('&' !in raw) return raw
    val out = StringBuilder(raw.length)
    var index = 0
    while (index < raw.length) {
        val char = raw[index]
        val end = if (char == '&') raw.indexOf(';', index) else -1
        if (end < 0 || end - index > MAX_ENTITY_LENGTH) {
            out.append(char)
            index++
            continue
        }
        val entity = raw.substring(index + 1, end)
        val decoded =
            when {
                entity == "amp" -> "&"
                entity == "lt" -> "<"
                entity == "gt" -> ">"
                entity == "quot" -> "\""
                entity == "apos" -> "'"
                entity.startsWith("#x", ignoreCase = true) -> entity.substring(2).toIntOrNull(16)?.codePointString()
                entity.startsWith("#") -> entity.substring(1).toIntOrNull()?.codePointString()
                else -> null
            }
        if (decoded == null) {
            out.append(char)
            index++
        } else {
            out.append(decoded)
            index = end + 1
        }
    }
    return out.toString()
}

private fun Int.codePointString(): String? {
    if (this < 0 || this > MAX_CODE_POINT || this in SURROGATE_RANGE) return null
    if (this < SUPPLEMENTARY_START) return this.toChar().toString()
    val offset = this - SUPPLEMENTARY_START
    return charArrayOf(
        (HIGH_SURROGATE_START + (offset shr 10)).toChar(),
        (LOW_SURROGATE_START + (offset and 0x3FF)).toChar(),
    ).concatToString()
}

/**
 * `Tue, 15 Nov 1994 08:12:31 GMT` → epoch milliseconds; null for anything else. WebDAV requires
 * RFC 1123 for `getlastmodified`, and a listing is not worth failing over a server that sends
 * another format.
 */
internal fun parseHttpDate(value: String): Long? {
    val parts =
        value
            .trim()
            .substringAfter(", ", missingDelimiterValue = value.trim())
            .split(' ')
            .filter(String::isNotEmpty)
    if (parts.size < 4) return null
    val day = parts[0].toIntOrNull() ?: return null
    val month = MONTHS.indexOf(parts[1].lowercase().take(3)).takeIf { it >= 0 }?.plus(1) ?: return null
    val year = parts[2].toIntOrNull() ?: return null
    val time = parts[3].split(':').map { it.toIntOrNull() ?: return null }
    if (time.size != 3 || day !in 1..31 || year < 1970) return null
    val (hour, minute, second) = time
    if (hour !in 0..23 || minute !in 0..59 || second !in 0..60) return null
    val days = daysFromCivil(year, month, day)
    return ((days * 24L + hour) * 60L + minute) * 60_000L + second * 1_000L
}

/** Days since 1970-01-01 for a proleptic Gregorian date (Howard Hinnant's algorithm). */
private fun daysFromCivil(
    year: Int,
    month: Int,
    day: Int,
): Long {
    val y = (if (month <= 2) year - 1 else year).toLong()
    val era = (if (y >= 0) y else y - 399) / 400
    val yearOfEra = y - era * 400
    val monthIndex = (month + 9) % 12
    val dayOfYear = (153 * monthIndex + 2) / 5 + day - 1
    val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
    return era * 146_097 + dayOfEra - 719_468
}

private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
private const val MAX_ENTITY_LENGTH = 10
private const val MAX_CODE_POINT = 0x10FFFF
private const val SUPPLEMENTARY_START = 0x10000
private const val HIGH_SURROGATE_START = 0xD800
private const val LOW_SURROGATE_START = 0xDC00
private val SURROGATE_RANGE = 0xD800..0xDFFF
