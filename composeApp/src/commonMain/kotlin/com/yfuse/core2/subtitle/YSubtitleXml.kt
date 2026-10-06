package com.yfuse.core2.subtitle

internal data class YXmlName(
    val namespace: String,
    val local: String,
)

internal sealed interface YXmlContent {
    data class Text(
        val value: String,
    ) : YXmlContent

    data class Element(
        val name: YXmlName,
        val attributes: Map<YXmlName, String>,
        val content: List<YXmlContent>,
    ) : YXmlContent {
        fun attribute(
            local: String,
            namespace: String = "",
        ): String? = attributes[YXmlName(namespace, local)]
    }
}

/** Bounded XML reader for sidecars. DTDs and custom entities are rejected, never expanded or fetched. */
internal class YSubtitleXml(
    private val text: String,
    private val ensureActive: () -> Unit,
) {
    private var offset = 0
    private var nodes = 0

    fun read(): YXmlContent.Element {
        require(text.length <= MAX_XML_CHARS) { "TTML exceeds the size limit" }
        text.forEachIndexed { index, char ->
            if (index % 4_096 == 0) ensureActive()
            require(char >= ' ' || char in "\n\r\t") { "Invalid XML control character" }
        }
        if (text.startsWith('\uFEFF')) offset++
        skipMisc()
        val root = element(mapOf("xml" to XML_NAMESPACE), 0)
        skipMisc()
        require(offset == text.length) { "XML must have exactly one root" }
        return root
    }

    private fun element(
        inherited: Map<String, String>,
        depth: Int,
    ): YXmlContent.Element {
        ensureActive()
        require(depth < MAX_XML_DEPTH && ++nodes <= MAX_XML_NODES) { "TTML XML nesting/node limit exceeded" }
        expect('<')
        val qualified = name()
        val rawAttributes = attributes()
        val namespaces = inherited.toMutableMap()
        for ((key, value) in rawAttributes) {
            if (key == "xmlns") {
                namespaces[""] = value
            } else if (key.startsWith("xmlns:")) {
                val prefix = key.substringAfter(':')
                require(
                    prefix != "xmlns" && (prefix != "xml" || value == XML_NAMESPACE),
                ) { "Invalid namespace binding" }
                namespaces[prefix] = value
            }
        }
        require(namespaces.size <= MAX_XML_ATTRIBUTES) { "Too many XML namespace bindings" }
        val expanded = linkedMapOf<YXmlName, String>()
        for ((key, value) in rawAttributes) {
            if (key == "xmlns" || key.startsWith("xmlns:")) continue
            require(
                expanded.put(resolve(key, namespaces, attribute = true), value) == null,
            ) { "Duplicate XML attribute" }
        }
        val children = mutableListOf<YXmlContent>()
        val resolved = resolve(qualified, namespaces, attribute = false)
        if (text.startsWith("/>", offset)) {
            offset += 2
            return YXmlContent.Element(resolved, expanded, children)
        }
        expect('>')
        content(children, namespaces, depth, qualified)
        return YXmlContent.Element(resolved, expanded, children)
    }

    private fun content(
        children: MutableList<YXmlContent>,
        namespaces: Map<String, String>,
        depth: Int,
        closing: String,
    ) {
        while (offset < text.length) {
            ensureActive()
            when {
                text.startsWith("</", offset) -> {
                    offset += 2
                    require(name() == closing) { "Mismatched XML closing tag" }
                    whitespace()
                    expect('>')
                    return
                }
                text.startsWith("<!--", offset) -> skipTo("-->", 4)
                text.startsWith("<?", offset) -> skipTo("?>", 2)
                text.startsWith("<![CDATA[", offset) -> {
                    val end = text.indexOf("]]>", offset + 9)
                    require(end >= 0) { "Unclosed CDATA" }
                    addText(children, text.substring(offset + 9, end))
                    offset = end + 3
                }
                text.startsWith("<!", offset) -> error("TTML DTD/entity declarations are not supported")
                text[offset] == '<' -> children.add(element(namespaces, depth + 1))
                else -> {
                    val end = text.indexOf('<', offset).let { if (it < 0) text.length else it }
                    addText(children, decodeEntities(text.substring(offset, end)))
                    offset = end
                }
            }
        }
        error("Unclosed XML element")
    }

    private fun addText(
        children: MutableList<YXmlContent>,
        value: String,
    ) {
        require(++nodes <= MAX_XML_NODES) { "TTML XML node limit exceeded" }
        children.add(YXmlContent.Text(value.replace("\r\n", "\n").replace('\r', '\n')))
    }

    private fun attributes(): Map<String, String> {
        val result = linkedMapOf<String, String>()
        while (true) {
            val hadSpace = whitespace()
            if (offset >= text.length || text[offset] == '>' || text[offset] == '/') return result
            require(hadSpace && result.size < MAX_XML_ATTRIBUTES) { "Invalid XML attributes" }
            val key = name()
            whitespace()
            expect('=')
            whitespace()
            val quote = text.getOrNull(offset++)
            require(quote == '\'' || quote == '"') { "XML attributes must be quoted" }
            val end = text.indexOf(quote, offset)
            require(end >= 0) { "Unclosed XML attribute" }
            val raw = text.substring(offset, end)
            require('<' !in raw) { "Invalid XML attribute text" }
            require(result.put(key, decodeEntities(raw)) == null) { "Duplicate XML attribute" }
            offset = end + 1
        }
    }

    private fun resolve(
        name: String,
        namespaces: Map<String, String>,
        attribute: Boolean,
    ): YXmlName {
        if (':' !in name) return YXmlName(if (attribute) "" else namespaces[""].orEmpty(), name)
        val parts = name.split(':')
        require(parts.size == 2 && parts.all(String::isNotEmpty)) { "Invalid XML qualified name" }
        val namespace = requireNotNull(namespaces[parts[0]]) { "Unbound XML namespace prefix" }
        require(namespace.isNotEmpty()) { "Empty prefixed XML namespace" }
        return YXmlName(namespace, parts[1])
    }

    private fun name(): String {
        val start = offset
        require(text.getOrNull(offset)?.let { it.isLetter() || it == '_' } == true) { "Invalid XML name" }
        while (offset < text.length && text[offset].let { it.isLetterOrDigit() || it in "_:-." }) offset++
        return text.substring(start, offset)
    }

    private fun skipMisc() {
        while (true) {
            whitespace()
            when {
                text.startsWith("<!--", offset) -> skipTo("-->", 4)
                text.startsWith("<?", offset) -> skipTo("?>", 2)
                else -> return
            }
        }
    }

    private fun skipTo(
        ending: String,
        prefix: Int,
    ) {
        ensureActive()
        val end = text.indexOf(ending, offset + prefix)
        require(end >= 0) { "Unclosed XML declaration/comment" }
        offset = end + ending.length
    }

    private fun whitespace(): Boolean {
        val start = offset
        while (offset < text.length && text[offset] in " \t\r\n") offset++
        return offset > start
    }

    private fun expect(char: Char) {
        require(text.getOrNull(offset) == char) { "Malformed XML" }
        offset++
    }

    private fun decodeEntities(value: String): String =
        buildString {
            var index = 0
            while (index < value.length) {
                if (index % 4_096 == 0) ensureActive()
                val char = value[index++]
                if (char != '&') {
                    require(char >= ' ' || char in "\n\r\t") { "Invalid XML control character" }
                    append(char)
                    continue
                }
                val end = value.indexOf(';', index)
                require(end in index..(index + 16).coerceAtMost(value.lastIndex)) { "Invalid XML entity" }
                val entity = value.substring(index, end)
                when (entity) {
                    "amp" -> append('&')
                    "lt" -> append('<')
                    "gt" -> append('>')
                    "apos" -> append('\'')
                    "quot" -> append('"')
                    else -> {
                        require(entity.startsWith('#')) { "Custom XML entities are not supported" }
                        val code =
                            if (entity.startsWith(
                                    "#x",
                                )
                            ) {
                                entity.substring(2).toIntOrNull(16)
                            } else {
                                entity.substring(1).toIntOrNull()
                            }
                        require(
                            code != null &&
                                (
                                    code in 0x20..0xd7ff ||
                                        code in 0xe000..0xfffd ||
                                        code in 0x10000..0x10ffff ||
                                        code in listOf(9, 10, 13)
                                ),
                        ) {
                            "Invalid XML character reference"
                        }
                        if (code <= 0xffff) {
                            append(code.toChar())
                        } else {
                            append(((code - 0x10000) / 1024 + 0xd800).toChar())
                            append(((code - 0x10000) % 1024 + 0xdc00).toChar())
                        }
                    }
                }
                index = end + 1
            }
        }
}

internal const val XML_NAMESPACE = "http://www.w3.org/XML/1998/namespace"
private const val MAX_XML_CHARS = 8 * 1024 * 1024
private const val MAX_XML_NODES = 100_000
private const val MAX_XML_DEPTH = 64
private const val MAX_XML_ATTRIBUTES = 64
