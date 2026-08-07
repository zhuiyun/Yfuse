package com.yfuse.watch.account

import java.net.InetAddress

internal sealed interface ClientIdentityResolution {
    data class Resolved(val value: String) : ClientIdentityResolution
    data object InvalidForwardedFor : ClientIdentityResolution
}

/**
 * The socket peer is authoritative. Only the local reverse proxy may supply the original
 * client address, and it must overwrite the header with exactly one IP literal. A public
 * peer's forwarding header is ignored, so it cannot rotate spoofed identities.
 */
internal fun resolveAccountClientIdentity(
    remoteHost: String,
    forwardedForValues: List<String>?,
): ClientIdentityResolution {
    val socketPeer = normalizeSocketPeer(remoteHost)
    if (!isLoopbackHost(socketPeer)) return ClientIdentityResolution.Resolved(socketPeer)
    if (forwardedForValues.isNullOrEmpty()) return ClientIdentityResolution.Resolved(socketPeer)
    if (forwardedForValues.size != 1) return ClientIdentityResolution.InvalidForwardedFor

    val forwarded = forwardedForValues.single().trim()
    if (forwarded.isEmpty() || forwarded.length > MAX_FORWARDED_IP_CHARS || ',' in forwarded) {
        return ClientIdentityResolution.InvalidForwardedFor
    }
    return normalizeIpLiteral(forwarded)
        ?.let(ClientIdentityResolution::Resolved)
        ?: ClientIdentityResolution.InvalidForwardedFor
}

internal fun isLoopbackHost(raw: String): Boolean {
    val normalized = normalizeSocketPeer(raw)
    if (normalized == "localhost") return true
    val literal = normalizeIpLiteral(normalized) ?: return false
    return runCatching { InetAddress.getByName(literal).isLoopbackAddress }.getOrDefault(false)
}

private fun normalizeSocketPeer(raw: String): String = raw
    .trim()
    .removeSurrounding("[", "]")
    .lowercase()
    .take(MAX_SOCKET_PEER_CHARS)
    .ifBlank { "unknown" }

private fun normalizeIpLiteral(raw: String): String? {
    normalizeIpv4(raw)?.let { return it }
    if (':' !in raw || raw.any { it !in IPV6_LITERAL_CHARS }) return null
    return runCatching { InetAddress.getByName(raw).hostAddress.substringBefore('%').lowercase() }
        .getOrNull()
}

private fun normalizeIpv4(raw: String): String? {
    if (raw.any { !it.isDigit() && it != '.' }) return null
    val parts = raw.split('.')
    if (parts.size != 4 || parts.any { it.isEmpty() || it.length > 3 }) return null
    val octets = parts.map { it.toIntOrNull() ?: return null }
    if (octets.any { it !in 0..255 }) return null
    return octets.joinToString(".")
}

private const val MAX_FORWARDED_IP_CHARS = 64
private const val MAX_SOCKET_PEER_CHARS = 128
private val IPV6_LITERAL_CHARS = ('0'..'9') + ('a'..'f') + ('A'..'F') + setOf(':', '.')
