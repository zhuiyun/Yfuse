package com.yfuse.core.account

import io.ktor.http.Url
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory bridge from the account session owner to authenticated services such as 一起看.
 *
 * The provider is deliberately suspendable: the repository can rotate an expiring access token
 * before a socket opens. Tokens are never copied into preferences, URLs, or invite payloads.
 */
class AccountAccessTokenSource(
    accountOrigin: String = ACCOUNT_BASE_URL,
) {
    private val trustedOrigin = Url(accountOrigin)
    private var provider: suspend () -> String? = { null }
    private var refreshProvider: suspend () -> String? = { null }
    private val _sessionAvailable = MutableStateFlow(false)
    val sessionAvailable: StateFlow<Boolean> = _sessionAvailable.asStateFlow()

    internal fun bind(
        provider: suspend () -> String?,
        refreshProvider: suspend () -> String?,
    ) {
        this.provider = provider
        this.refreshProvider = refreshProvider
    }

    internal fun markAvailable() {
        _sessionAvailable.value = true
    }

    internal fun markUnavailable() {
        _sessionAvailable.value = false
    }

    fun trusts(endpoint: String): Boolean = endpoint.sameServiceOriginAs(trustedOrigin)

    suspend fun validAccessTokenFor(endpoint: String): String? {
        if (!trusts(endpoint)) return null
        return provider()?.takeIf(String::isNotBlank)
    }

    suspend fun refreshAccessTokenFor(endpoint: String): String? {
        if (!trusts(endpoint)) return null
        return refreshProvider()?.takeIf(String::isNotBlank)
    }
}

/** Prevents a user-supplied relay or invite endpoint from receiving the central account token. */
internal fun String.sameServiceOriginAs(accountOrigin: Url): Boolean {
    val candidate = runCatching { Url(trim().trimEnd('/')) }.getOrNull() ?: return false
    if (candidate.protocol.name !in setOf("https", "wss")) return false
    return candidate.host.equals(accountOrigin.host, ignoreCase = true) &&
        candidate.port == accountOrigin.port
}
