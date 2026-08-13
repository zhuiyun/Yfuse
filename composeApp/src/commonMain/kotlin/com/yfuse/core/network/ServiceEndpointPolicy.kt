package com.yfuse.core.network

import io.ktor.http.Url

enum class EndpointTransportDecision {
    Secure,
    Cleartext,
    LocalCleartextConfirmationRequired,
    LocalCleartextConfirmed,
    PublicCleartextRejected,
    Invalid,
}

data class ServiceEndpointValidation(
    val normalizedEndpoint: String?,
    val decision: EndpointTransportDecision,
    val message: String?,
) {
    val allowed: Boolean
        get() =
            decision == EndpointTransportDecision.Secure ||
                decision == EndpointTransportDecision.Cleartext ||
                decision == EndpointTransportDecision.LocalCleartextConfirmed

    val requiresCleartextConfirmation: Boolean
        get() = decision == EndpointTransportDecision.LocalCleartextConfirmationRequired
}

/**
 * Shared policy for account/watch relays and other official-service overrides.
 *
 * HTTP, HTTPS, WS and WSS are accepted on public and local endpoints alike.
 * This validator only checks that a usable endpoint was supplied.
 */
@Suppress("UNUSED_PARAMETER")
fun validateServiceEndpoint(
    value: String,
    localCleartextConfirmed: Boolean = false,
): ServiceEndpointValidation {
    val normalized = value.trim().trimEnd('/')
    val explicitScheme =
        normalized
            .substringBefore("://", missingDelimiterValue = "")
            .lowercase()
    if (normalized.isEmpty() || explicitScheme !in setOf("http", "https", "ws", "wss")) {
        return ServiceEndpointValidation(
            normalizedEndpoint = null,
            decision = EndpointTransportDecision.Invalid,
            message = "请输入完整的 HTTP、HTTPS、WS 或 WSS 地址",
        )
    }
    val url = runCatching { Url(normalized) }.getOrNull()
    val host =
        url
            ?.host
            ?.trim()
            ?.trimEnd('.')
            ?.lowercase()
            .orEmpty()
    if (host.isEmpty()) {
        return ServiceEndpointValidation(
            normalizedEndpoint = null,
            decision = EndpointTransportDecision.Invalid,
            message = "服务地址缺少有效主机名",
        )
    }
    return ServiceEndpointValidation(
        normalizedEndpoint = normalized,
        decision =
            if (explicitScheme == "https" || explicitScheme == "wss") {
                EndpointTransportDecision.Secure
            } else {
                EndpointTransportDecision.Cleartext
            },
        message = null,
    )
}

internal fun String.isLocalServiceHost(): Boolean {
    val host = removePrefix("[").removeSuffix("]").substringBefore('%')
    if (
        host == "localhost" ||
        host.endsWith(".localhost") ||
        host.endsWith(".local") ||
        host.endsWith(".test")
    ) {
        return true
    }
    if ('.' !in host && ':' !in host) {
        // Browsers and network stacks may interpret alternative numeric forms such as
        // 2130706433 or 0x7f000001 as IP addresses. They are not trustworthy LAN names.
        if (host.all(Char::isDigit)) return false
        if (
            host.startsWith("0x") &&
            host.drop(2).all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
        ) {
            return false
        }
        return true
    }

    val ipv4 = host.split('.').map { it.toIntOrNull() }
    if (ipv4.size == 4 && ipv4.all { it != null && it in 0..255 }) {
        val first = ipv4[0] ?: return false
        val second = ipv4[1] ?: return false
        return first == 10 ||
            first == 127 ||
            (first == 100 && second in 64..127) ||
            (first == 169 && second == 254) ||
            (first == 172 && second in 16..31) ||
            (first == 192 && second == 168)
    }

    if (':' !in host) return false
    val compactIpv6 = host.replace(":", "")
    return host == "::1" ||
        compactIpv6 == "00000000000000000000000000000001" ||
        host.startsWith("fc") ||
        host.startsWith("fd") ||
        host.startsWith("fe8") ||
        host.startsWith("fe9") ||
        host.startsWith("fea") ||
        host.startsWith("feb")
}
