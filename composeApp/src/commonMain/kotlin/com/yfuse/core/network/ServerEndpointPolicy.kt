package com.yfuse.core.network

import com.yfuse.core.model.SavedServer
import io.ktor.http.Url

private val retiredEmbyHosts =
    setOf(
        "gf.emby.yun",
        "gy.emby.yun",
    )

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

internal const val LOCAL_CLEARTEXT_WARNING =
    "局域网内的明文 HTTP 不加密：同一网络上的设备可以看到你的账号密码与观看内容。"
internal const val PUBLIC_CLEARTEXT_WARNING =
    "公网明文 HTTP 会把账号密码与观看内容暴露给沿途每一个网络节点，强烈建议改用 HTTPS。"

/**
 * Emby endpoints may use HTTP or HTTPS on any host and port: transport choice belongs to the
 * user. What the app owes them is an honest statement of what plain HTTP means on the network
 * the address lives on, and a consent that is remembered with the server rather than assumed.
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
    val local = host.isLocalServiceHost()
    val warning = if (local) LOCAL_CLEARTEXT_WARNING else PUBLIC_CLEARTEXT_WARNING
    return if (localCleartextConfirmed) {
        ServiceEndpointValidation(
            normalizedEndpoint = normalized,
            decision =
                if (local) {
                    EndpointTransportDecision.LocalCleartextConfirmed
                } else {
                    EndpointTransportDecision.PublicCleartextConfirmed
                },
            message = null,
            cleartextWarning = warning,
        )
    } else {
        ServiceEndpointValidation(
            normalizedEndpoint = normalized,
            decision =
                if (local) {
                    EndpointTransportDecision.LocalCleartextConfirmationRequired
                } else {
                    EndpointTransportDecision.PublicCleartextConfirmationRequired
                },
            message = "请先确认明文 HTTP 连接的风险",
            cleartextWarning = warning,
        )
    }
}
