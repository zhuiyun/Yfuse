package com.yfuse.core.account

import io.ktor.client.call.body
import io.ktor.client.statement.HttpResponse
import kotlinx.coroutines.CancellationException

/** Malformed server errors may use a fallback, but cancellation must never become an HTTP 401. */
internal suspend fun HttpResponse.decodeAccountError(fallbackMessage: String): AccountApiException {
    val envelope =
        try {
            body<ErrorEnvelope>()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    return AccountApiException(
        code = envelope?.error?.code ?: "http_${status.value}",
        message = envelope?.error?.message ?: fallbackMessage,
        status = status,
        currentVersion = envelope?.error?.currentVersion,
    )
}
