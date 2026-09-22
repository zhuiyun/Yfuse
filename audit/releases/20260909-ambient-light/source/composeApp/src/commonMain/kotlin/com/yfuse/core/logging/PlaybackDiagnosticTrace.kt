package com.yfuse.core.logging

import com.yfuse.core.security.platformCryptoPrimitives

/** Correlates startup stages without recording a playback/server identifier in exported logs. */
internal fun playbackDiagnosticTrace(value: String?): String {
    if (value.isNullOrBlank()) return ""
    val digest = traceCrypto.sha256(traceSalt + value.encodeToByteArray())
    return buildString(25) {
        append('p')
        for (index in 0 until 12) {
            val byte = digest[index].toInt() and 0xff
            append(TRACE_HEX[byte ushr 4])
            append(TRACE_HEX[byte and 0xf])
        }
    }
}

private val traceCrypto by lazy { platformCryptoPrimitives() }
private val traceSalt by lazy { traceCrypto.randomBytes(32) }
private const val TRACE_HEX = "0123456789abcdef"
