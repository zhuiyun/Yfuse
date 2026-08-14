package com.yfuse.watch.migration

import com.yfuse.watch.account.AccountWorkExecutor
import com.yfuse.watch.account.AccountWorkRejectedException
import com.yfuse.watch.account.ClientIdentityResolution
import com.yfuse.watch.account.resolveAccountClientIdentity
import com.yfuse.watch.installServiceHealthEndpoint
import com.yfuse.watch.isSecureServiceTransport
import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.log
import io.ktor.server.plugins.origin
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val relayJson =
    Json {
        ignoreUnknownKeys = false
        encodeDefaults = true
    }

internal fun Route.migrationRelayRoutes(
    backend: MigrationRelayBackend,
    workExecutor: AccountWorkExecutor,
    clientIpResolver: ((ApplicationCall) -> String)? = null,
    trustProxyHeaders: Boolean = false,
) {
    installServiceHealthEndpoint(backend, workExecutor)
    route("/api/v1/migration-relays") {
        post {
            call.handleRelayEndpoint(HttpStatusCode.Created, trustProxyHeaders) {
                val request = call.receiveRelayJson<CreateMigrationRelayRequest>()
                val clientIp = call.relayClientIp(clientIpResolver, trustProxyHeaders)
                workExecutor.execute { backend.create(request, clientIp) }
            }
        }
        post("/redeem") {
            call.handleRelayEndpoint(HttpStatusCode.OK, trustProxyHeaders) {
                val request = call.receiveRelayJson<RedeemMigrationRelayRequest>()
                val clientIp = call.relayClientIp(clientIpResolver, trustProxyHeaders)
                workExecutor.execute { backend.redeem(request, clientIp) }
            }
        }
    }
}

private suspend inline fun <reified T> ApplicationCall.handleRelayEndpoint(
    successStatus: HttpStatusCode,
    trustProxyHeaders: Boolean,
    block: suspend () -> T,
) {
    response.header(HttpHeaders.CacheControl, "no-store, max-age=0")
    response.header(HttpHeaders.Pragma, "no-cache")
    try {
        if (!isSecureServiceTransport(trustProxyHeaders)) {
            throw MigrationRelayException("secure_transport_required", "迁移服务必须使用 HTTPS")
        }
        val value = block()
        respondText(relayJson.encodeToString(value), ContentType.Application.Json, successStatus)
    } catch (error: MigrationRelayException) {
        val status = if (error.rateLimited) HttpStatusCode.TooManyRequests else HttpStatusCode.BadRequest
        respondText(
            relayJson.encodeToString(MigrationRelayErrorResponse(error.errorCode, error.message)),
            ContentType.Application.Json,
            status,
        )
    } catch (_: AccountWorkRejectedException) {
        response.header(HttpHeaders.RetryAfter, "1")
        respondText(
            relayJson.encodeToString(
                MigrationRelayErrorResponse("relay_busy", "迁移服务繁忙，请稍后重试"),
            ),
            ContentType.Application.Json,
            HttpStatusCode.ServiceUnavailable,
        )
    } catch (failure: CancellationException) {
        throw failure
    } catch (failure: Exception) {
        // Never log request bodies or exception messages: relay requests contain transfer keys.
        application.log.error("Migration relay request failed (${failure::class.simpleName})")
        respondText(
            relayJson.encodeToString(
                MigrationRelayErrorResponse("internal_error", "迁移服务暂时无法处理请求"),
            ),
            ContentType.Application.Json,
            HttpStatusCode.InternalServerError,
        )
    }
}

private suspend inline fun <reified T> ApplicationCall.receiveRelayJson(): T {
    if (request.headers[HttpHeaders.ContentType]?.substringBefore(';') != ContentType.Application.Json.toString()) {
        throw MigrationRelayException("invalid_request", "迁移请求无效")
    }
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(1_024)
    var total = 0
    val channel = receiveChannel()
    while (!channel.isClosedForRead) {
        val read = channel.readAvailable(buffer)
        if (read < 0) break
        if (read == 0) continue
        total += read
        if (total > MAX_RELAY_REQUEST_BYTES) {
            throw MigrationRelayException("invalid_request", "迁移请求无效")
        }
        output.write(buffer, 0, read)
    }
    return try {
        relayJson.decodeFromString(output.toString(Charsets.UTF_8.name()))
    } catch (_: SerializationException) {
        throw MigrationRelayException("invalid_request", "迁移请求无效")
    }
}

private fun ApplicationCall.relayClientIp(
    resolver: ((ApplicationCall) -> String)?,
    trustProxyHeaders: Boolean,
): String =
    resolver
        ?.invoke(this)
        ?.trim()
        ?.take(128)
        ?.ifBlank { null }
        ?: when (
            val identity =
                resolveAccountClientIdentity(
                    remoteHost = request.origin.remoteHost,
                    forwardedForValues =
                        request.headers.getAll("X-Forwarded-For").takeIf { trustProxyHeaders },
                )
        ) {
            is ClientIdentityResolution.Resolved -> identity.value
            ClientIdentityResolution.InvalidForwardedFor -> "invalid-forwarded-client"
        }.take(128)

private const val MAX_RELAY_REQUEST_BYTES = 1_024
