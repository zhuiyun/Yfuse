package com.yfuse.watch.qoe

import com.yfuse.watch.isSecureServiceTransport
import com.yfuse.watch.protocol.AnonymousPlaybackQoeReport
import com.yfuse.watch.protocol.QoeProtocol
import com.yfuse.watch.resolveClientIp
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.plugins.origin
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.ZoneOffset

private val qoeJson =
    Json {
        ignoreUnknownKeys = false
        encodeDefaults = true
    }

internal fun Route.qoeRoutes(
    backend: QoeAggregateBackend,
    limiter: QoeRateLimiter = QoeRateLimiter(),
    clientIpResolver: ((ApplicationCall) -> String)? = null,
    trustProxyHeaders: Boolean = false,
    dayUtc: () -> String = { LocalDate.now(ZoneOffset.UTC).toString() },
) {
    post("/api/v1/qoe") {
        call.response.header(HttpHeaders.CacheControl, "no-store, max-age=0")
        call.response.header(HttpHeaders.Pragma, "no-cache")
        try {
            if (!call.isSecureServiceTransport(trustProxyHeaders)) {
                return@post call.qoeError(HttpStatusCode.BadRequest, "secure_transport_required")
            }
            if (
                call.request.headers[HttpHeaders.ContentType]
                    ?.substringBefore(';') != ContentType.Application.Json.toString()
            ) {
                return@post call.qoeError(HttpStatusCode.BadRequest, "invalid_request")
            }
            val clientIp =
                clientIpResolver
                    ?.invoke(call)
                    ?.trim()
                    ?.take(128)
                    ?.ifBlank { null }
                    ?: resolveClientIp(
                        remoteHost = call.request.origin.remoteHost,
                        xForwardedFor = call.request.headers["X-Forwarded-For"],
                        forwarded = call.request.headers["Forwarded"],
                        trustProxyHeaders = trustProxyHeaders,
                    )
            if (!limiter.tryAcquire(clientIp)) {
                call.response.header(HttpHeaders.RetryAfter, "3600")
                return@post call.qoeError(HttpStatusCode.TooManyRequests, "rate_limited")
            }
            val report = call.receiveQoeReport()
            if (!QoeProtocol.isValid(report)) {
                return@post call.qoeError(HttpStatusCode.BadRequest, "invalid_request")
            }
            withContext(Dispatchers.IO) { backend.record(dayUtc(), report) }
            call.respondText(
                "{\"accepted\":true}",
                ContentType.Application.Json,
                HttpStatusCode.Accepted,
            )
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: QoeRequestException) {
            call.qoeError(HttpStatusCode.BadRequest, "invalid_request")
        } catch (_: Exception) {
            // The body contains no credentials, but still avoid logging individual report values.
            call.qoeError(HttpStatusCode.InternalServerError, "internal_error")
        }
    }
}

internal class QoeRateLimiter(
    private val maxReportsPerWindow: Int = 60,
    private val windowMs: Long = 60L * 60L * 1_000L,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
) {
    private data class Window(
        var startedAt: Long,
        var count: Int,
    )

    private val lock = Any()
    private val windows = linkedMapOf<String, Window>()

    fun tryAcquire(clientIp: String): Boolean =
        synchronized(lock) {
            val now = nowEpochMs()
            if (windows.size >= MAX_RATE_LIMIT_CLIENTS) {
                windows.entries.removeAll { now - it.value.startedAt >= windowMs }
                while (windows.size >= MAX_RATE_LIMIT_CLIENTS) {
                    windows.remove(windows.entries.first().key)
                }
            }
            val window = windows.getOrPut(clientIp) { Window(now, 0) }
            if (now - window.startedAt >= windowMs) {
                window.startedAt = now
                window.count = 0
            }
            if (window.count >= maxReportsPerWindow) return@synchronized false
            window.count++
            true
        }
}

private suspend fun ApplicationCall.receiveQoeReport(): AnonymousPlaybackQoeReport {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(512)
    var total = 0
    val channel = receiveChannel()
    while (!channel.isClosedForRead) {
        val read = channel.readAvailable(buffer)
        if (read < 0) break
        if (read == 0) continue
        total += read
        if (total > MAX_QOE_REQUEST_BYTES) throw QoeRequestException()
        output.write(buffer, 0, read)
    }
    return try {
        qoeJson.decodeFromString(output.toString(Charsets.UTF_8.name()))
    } catch (_: SerializationException) {
        throw QoeRequestException()
    }
}

private suspend fun ApplicationCall.qoeError(
    status: HttpStatusCode,
    code: String,
) = respondText("{\"error\":\"$code\"}", ContentType.Application.Json, status)

private class QoeRequestException : IllegalArgumentException()

private const val MAX_QOE_REQUEST_BYTES = 2_048
private const val MAX_RATE_LIMIT_CLIENTS = 10_000
