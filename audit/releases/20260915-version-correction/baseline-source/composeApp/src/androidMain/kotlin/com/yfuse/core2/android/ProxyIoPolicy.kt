package com.yfuse.core2.android

import java.io.IOException
import java.io.Reader

/** Bound allocation while reading, including lines with no newline. */
internal fun readProxyLine(
    reader: Reader,
    maximumChars: Int,
): String? {
    val line = StringBuilder(minOf(maximumChars, 128))
    while (true) {
        val value = reader.read()
        if (value == -1) return line.toString().takeIf { it.isNotEmpty() }
        if (value == '\n'.code) return line.toString().removeSuffix("\r")
        if (line.length >= maximumChars) throw IOException("Proxy request line exceeds its buffer")
        line.append(value.toChar())
    }
}
