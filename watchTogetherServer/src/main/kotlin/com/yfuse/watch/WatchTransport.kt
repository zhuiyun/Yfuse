package com.yfuse.watch

import com.yfuse.watch.account.isLoopbackHost
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin

/**
 * Resolves the quota identity. Forwarding headers are ignored unless the deployment opts in;
 * otherwise a direct client could rotate a spoofed header to bypass the limit.
 */
internal fun resolveClientIp(
    remoteHost: String,
    xForwardedFor: String?,
    forwarded: String?,
    trustProxyHeaders: Boolean,
): String {
    val direct = remoteHost.trim().take(128).ifBlank { "unknown" }
    if (!trustProxyHeaders) return direct

    val forwardedFor =
        xForwardedFor
            ?.substringBefore(',')
            ?.let(::normalizeForwardedAddress)
            ?: forwarded
                ?.substringBefore(',')
                ?.split(';')
                ?.firstNotNullOfOrNull { part ->
                    part
                        .substringAfter('=', missingDelimiterValue = "")
                        .takeIf {
                            part.substringBefore('=').trim().equals("for", ignoreCase = true)
                        }?.let(::normalizeForwardedAddress)
                }
    return forwardedFor ?: direct
}

/** Sensitive account credentials may only enter through TLS or the trusted local proxy. */
internal fun ApplicationCall.isSecureServiceTransport(trustProxyHeaders: Boolean): Boolean {
    if (request.origin.scheme.equals("https", ignoreCase = true)) return true
    if (!isLoopbackHost(request.origin.remoteHost)) return false
    val forwardedProto = request.headers["X-Forwarded-Proto"] ?: return true
    if (!trustProxyHeaders || ',' in forwardedProto) return false
    return forwardedProto.trim().equals("https", ignoreCase = true)
}

private fun normalizeForwardedAddress(raw: String): String? {
    var value = raw.trim().removeSurrounding("\"")
    if (value.equals("unknown", ignoreCase = true) || value.startsWith('_')) return null
    if (value.startsWith('[')) {
        value = value.substringAfter('[').substringBefore(']')
    } else if (value.count { it == ':' } == 1 && value.substringBeforeLast(':').contains('.')) {
        value = value.substringBeforeLast(':')
    }
    return value
        .trim()
        .lowercase()
        .takeIf { it.isNotBlank() && it.length <= 128 }
}
