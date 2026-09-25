package com.yfuse.core.data

import com.yfuse.core.logging.AppLog
import com.yfuse.core.network.EmbyError
import com.yfuse.core.network.EmbyErrorException
import com.yfuse.core.util.isUiThread
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.ResponseException
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.encodeURLPathPart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * One path segment of an Emby-compatible route.
 *
 * Ids arrive from the server, from saved state, and — for watch-together — from another
 * participant's room; encoding every one the same way means none of them can carry a `/`,
 * `?` or `#` into the request and address a different endpoint with this session's token.
 */
internal fun embyPath(id: String): String = id.encodeURLPathPart()

/**
 * [runCatching] for suspending work: cancellation propagates instead of becoming a failed
 * [Result] that callers would record as a business error.
 */
internal inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        Result.failure(error)
    }

internal inline fun <T> Result<T>.recoverCatchingCancellable(transform: (Throwable) -> T): Result<T> =
    exceptionOrNull()?.let { runCatchingCancellable { transform(it) } } ?: this

/**
 * Shared boundary for Emby-compatible API calls.
 *
 * Keeping error mapping here lets focused services preserve the exact failure contract that
 * [EmbyRepository] exposed before it was split.
 */
internal suspend fun <T> embyApiCall(
    operation: String,
    block: suspend () -> T,
): Result<T> =
    offUiThread {
        try {
            Result.success(block())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            val mapped = error.toEmbyError()
            val fromCooldown = (error as? EmbyErrorException)?.fromCooldown == true
            val attributes =
                mapOf(
                    "operation" to operation,
                    "error" to mapped.toString(),
                )
            // Ktor's ResponseException message may contain the complete HTML response body. Keep the
            // mapped status/domain error in diagnostics, never an intermediary page with host/IP data.
            val diagnosticThrowable =
                if (error is ResponseException) EmbyErrorException(mapped) else error
            when {
                // The client answered from a cooldown the server's own failure started, which was
                // logged then. One error per short-circuited request buried everything else.
                fromCooldown ->
                    AppLog.debug(
                        category = "emby",
                        event = "request_cooled_down",
                        message = "Emby operation skipped while its server is cooling down",
                        attributes = attributes,
                    )
                // A missing item is an answer, not a malfunction: lookups that probe for an item the
                // server may not hold are expected to miss, and logging those at error level buries
                // the failures that do need attention.
                mapped == EmbyError.NotFound ->
                    AppLog.warning(
                        category = "emby",
                        event = "request_not_found",
                        message = "Emby operation addressed an item the server does not have",
                        throwable = diagnosticThrowable,
                        attributes = attributes,
                    )
                else ->
                    AppLog.error(
                        category = "emby",
                        event = "request_failed",
                        message = "Emby operation failed",
                        throwable = diagnosticThrowable,
                        attributes = attributes,
                    )
            }
            Result.failure(EmbyErrorException(mapped, fromCooldown))
        }
    }

/**
 * Runs [block] on a worker when it was called on the UI thread, and in place otherwise.
 *
 * Stores call the repository from the main dispatcher, and Ktor reads and decodes a response body in
 * the calling coroutine: a library page or a two-thousand-item sync snapshot was parsed on the UI
 * thread. Callers already off it - including every nested call - pay only the thread check.
 */
internal suspend fun <T> offUiThread(
    onUiThread: Boolean = isUiThread(),
    block: suspend () -> T,
): T = if (onUiThread) withContext(Dispatchers.Default) { block() } else block()

private suspend fun Throwable.toEmbyError(): EmbyError =
    when (this) {
        is EmbyErrorException -> error
        is ResponseException ->
            when (response.status.value) {
                401 -> EmbyError.Unauthorized
                403 -> forbiddenError()
                404, 410 -> EmbyError.NotFound
                in 500..599 -> EmbyError.Server(response.status.value)
                else -> EmbyError.Unknown("HTTP ${response.status.value}")
            }

        is IOException -> transportError()
        else -> EmbyError.Unknown(message ?: "无法解析服务器响应")
    }

/**
 * Names the transport failure when the engine reported one the user can act on, so the message
 * can point at the address, the port or the certificate instead of only at the network.
 *
 * Engines wrap some of these, so a few causes are inspected. Timeouts come first: Ktor's connect
 * timeout is itself a [ConnectException] on the JVM. A TLS read error on an established
 * connection is an SSLException too, so only a failed handshake or peer check counts as a
 * certificate problem. And an offline device reports "network unreachable" through the same
 * [ConnectException] a closed port does, so only a refusal the OS named as one is called refused.
 */
private fun IOException.transportError(): EmbyError.Unreachable =
    generateSequence<Throwable>(this) { it.cause }
        .take(8)
        .firstNotNullOfOrNull { cause ->
            when (cause) {
                is HttpRequestTimeoutException,
                is ConnectTimeoutException,
                is SocketTimeoutException,
                -> EmbyError.Timeout
                is SSLHandshakeException,
                is SSLPeerUnverifiedException,
                -> EmbyError.Certificate
                is UnknownHostException -> EmbyError.HostNotFound
                is ConnectException -> EmbyError.ConnectionRefused.takeIf { cause.reportsRefusal() }
                else -> null
            }
        } ?: EmbyError.Network

// Android spells the errno ("ECONNREFUSED (Connection refused)"); the JVM only the text.
private fun Throwable.reportsRefusal(): Boolean =
    message.orEmpty().let { "ECONNREFUSED" in it || it.contains("Connection refused", ignoreCase = true) }

private suspend fun ResponseException.forbiddenError(): EmbyError {
    val serverHeader = response.headers[HttpHeaders.Server].orEmpty()
    val responseText =
        runCatching { response.bodyAsText() }
            .getOrDefault(message.orEmpty())
            .take(8_192)
            .lowercase()
    val cloudflare =
        serverHeader.contains("cloudflare", ignoreCase = true) ||
            response.headers["CF-Ray"] != null ||
            "cloudflare" in responseText
    val htmlResponse =
        response.headers[HttpHeaders.ContentType]
            ?.contains("text/html", ignoreCase = true) == true ||
            "<!doctype html" in responseText ||
            "<html" in responseText
    val accessBlock =
        cloudflare ||
            htmlResponse ||
            listOf(
                "sorry, you have been blocked",
                "access denied",
                "request blocked",
                "security policy",
                "客户端已被屏蔽",
                "已被屏蔽",
            ).any(responseText::contains)

    return if (accessBlock) {
        EmbyError.AccessDenied(provider = "Cloudflare".takeIf { cloudflare })
    } else {
        EmbyError.Unauthorized
    }
}
