package com.yfuse.core.network

import com.yfuse.core.model.SavedServer
import io.ktor.http.Url

private val retiredEmbyHosts =
    setOf(
        "gf.emby.yun",
        "gy.emby.yun",
    )

/**
 * Returns a user-facing reason when an endpoint is known to be permanently unavailable.
 *
 * These two legacy addresses use `.yun`, which is not a public DNS suffix. Keeping the saved
 * entries lets the user edit or remove them, but sending a request can only spend the full DNS
 * or connection budget. Matching the parsed host (not the raw URL) handles ports without
 * accidentally rejecting a longer, otherwise valid hostname.
 */
internal fun SavedServer.knownUnavailableEndpointReason(): String? {
    val host =
        runCatching { Url(baseUrl).host.trimEnd('.').lowercase() }.getOrNull()
            ?: return null
    return if (host in retiredEmbyHosts) {
        "服务器地址已失效，请编辑或移除该服务器"
    } else {
        null
    }
}

/**
 * Emby endpoints may use HTTP when the user explicitly acknowledges the cleartext risk.
 * HTTPS is valid without acknowledgement; HTTP is supported for both LAN and public
 * self-hosted servers because users may expose Emby on arbitrary addresses and ports.
 */
fun validateEmbyServerEndpoint(
    value: String,
    localCleartextConfirmed: Boolean = false,
): ServiceEndpointValidation {
    val normalized = value.trim().trimEnd('/')
    val url = runCatching { Url(normalized) }.getOrNull()
    val scheme = url?.protocol?.name?.lowercase()
    val host =
        url
            ?.host
            ?.trim()
            ?.trimEnd('.')
            ?.lowercase()
            .orEmpty()
    if (
        normalized.isEmpty() ||
        scheme !in setOf("http", "https") ||
        host.isEmpty() ||
        url?.user != null ||
        url?.password != null ||
        url?.fragment?.isNotEmpty() == true ||
        url?.parameters?.isEmpty() == false
    ) {
        return ServiceEndpointValidation(
            normalizedEndpoint = null,
            decision = EndpointTransportDecision.Invalid,
            message = "请输入完整的 HTTP 或 HTTPS 地址",
        )
    }
    if (scheme == "https") {
        return ServiceEndpointValidation(
            normalizedEndpoint = normalized,
            decision = EndpointTransportDecision.Secure,
            message = null,
        )
    }
    return if (localCleartextConfirmed) {
        ServiceEndpointValidation(
            normalizedEndpoint = normalized,
            decision = EndpointTransportDecision.LocalCleartextConfirmed,
            message = null,
        )
    } else {
        ServiceEndpointValidation(
            normalizedEndpoint = normalized,
            decision = EndpointTransportDecision.LocalCleartextConfirmationRequired,
            message = "HTTP 未加密，会暴露账号与令牌，请确认风险后继续",
        )
    }
}
