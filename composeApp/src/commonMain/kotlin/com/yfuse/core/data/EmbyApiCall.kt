package com.yfuse.core.data

import com.yfuse.core.logging.AppLog
import com.yfuse.core.network.EmbyError
import com.yfuse.core.network.EmbyErrorException
import io.ktor.client.plugins.ResponseException
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.io.IOException

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
    try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        val mapped = error.toEmbyError()
        val attributes =
            mapOf(
                "operation" to operation,
                "error" to mapped.toString(),
            )
        // A missing item is an answer, not a malfunction: lookups that probe for an item the
        // server may not hold are expected to miss, and logging those at error level buries the
        // failures that do need attention.
        if (mapped == EmbyError.NotFound) {
            AppLog.warning(
                category = "emby",
                event = "request_not_found",
                message = "Emby operation addressed an item the server does not have",
                throwable = error,
                attributes = attributes,
            )
        } else {
            AppLog.error(
                category = "emby",
                event = "request_failed",
                message = "Emby operation failed",
                throwable = error,
                attributes = attributes,
            )
        }
        Result.failure(EmbyErrorException(mapped))
    }

private suspend fun Throwable.toEmbyError(): EmbyError =
    when (this) {
        is ResponseException ->
            when (response.status.value) {
                401 -> EmbyError.Unauthorized
                403 -> forbiddenError()
                404 -> EmbyError.NotFound
                in 500..599 -> EmbyError.Server(response.status.value)
                else -> EmbyError.Unknown("HTTP ${response.status.value}")
            }

        is IOException -> EmbyError.Network
        else -> EmbyError.Unknown(message ?: "无法解析服务器响应")
    }

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
