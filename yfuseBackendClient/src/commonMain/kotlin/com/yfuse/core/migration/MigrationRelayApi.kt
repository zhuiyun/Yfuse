package com.yfuse.core.migration

import com.yfuse.backend.BackendAccess
import com.yfuse.backend.BackendEndpoints
import com.yfuse.backend.BackendFeature
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.plugin
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64

@Serializable
data class MigrationRelayTicket(
    val code: String,
    val expiresAtEpochMs: Long,
) {
    init {
        require(code.length == 6 && code.all { it in '0'..'9' }) { "服务返回了无效的迁移码" }
    }
}

class MigrationRelayApiException(
    val errorCode: String,
    override val message: String,
    val status: HttpStatusCode,
) : Exception(message)

class MigrationRelayApi(
    private val client: HttpClient,
    baseUrl: String = BackendEndpoints.ORIGIN,
    private val access: BackendAccess = BackendAccess.Default,
    /** Only a factory transferring ownership supplies this engine; injected clients remain caller-owned. */
    private val ownedEngine: HttpClientEngine? = null,
) {
    private val origin =
        baseUrl.trimEnd('/').also {
            require(it.startsWith("https://")) { "迁移服务必须使用 HTTPS" }
        }

    /** Injected clients and their engines belong to the caller and must stay usable after close. */
    fun close() {
        if (ownedEngine != null) {
            try {
                client.close()
            } finally {
                ownedEngine.close()
            }
        }
    }

    suspend fun create(
        relayId: String,
        transferSecret: String,
        payloadSha256: String,
    ): MigrationRelayTicket {
        access.requireEnabled(BackendFeature.Migration)
        return client
            .post("$origin/api/v1/migration-relays") {
                contentType(ContentType.Application.Json)
                setBody(CreateRelayRequest(relayId, transferSecret, payloadSha256))
            }.decoded()
    }

    /** A successful redemption consumes the key even if later local decryption fails. */
    suspend fun redeem(
        relayId: String,
        code: String,
        payloadSha256: String,
    ): ByteArray {
        access.requireEnabled(BackendFeature.Migration)
        require(code.length == 6 && code.all { it in '0'..'9' }) { "请输入 6 位数字迁移码" }
        val response: RedeemRelayResponse =
            client
                .post("$origin/api/v1/migration-relays/redeem") {
                    contentType(ContentType.Application.Json)
                    setBody(RedeemRelayRequest(relayId, code, payloadSha256))
                }.decoded()
        return response.transferSecret.decodeTransferSecret()
    }
}

fun createMigrationRelayClient(
    engine: HttpClientEngine,
    trustedOrigin: String = BackendEndpoints.ORIGIN,
    access: BackendAccess = BackendAccess.Default,
): HttpClient =
    HttpClient(engine) {
        expectSuccess = false
        install(HttpTimeout) {
            requestTimeoutMillis = 15_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 15_000
        }
        install(ContentNegotiation) {
            json(
                Json {
                    ignoreUnknownKeys = true
                    encodeDefaults = true
                },
            )
        }
    }.also { client ->
        val trusted = Url(trustedOrigin)
        client.plugin(HttpSend).intercept { request ->
            access.requireEnabled(BackendFeature.Migration)
            val target = request.url.build()
            check(
                target.protocol == URLProtocol.HTTPS &&
                    target.host.equals(trusted.host, ignoreCase = true) &&
                    target.specifiedPortOrDefault() == trusted.specifiedPortOrDefault(),
            ) { "迁移密钥禁止发送到非官方服务" }
            execute(request)
        }
    }

private fun Url.specifiedPortOrDefault(): Int = if (specifiedPort == 0) protocol.defaultPort else specifiedPort

@Serializable
private data class CreateRelayRequest(
    val relayId: String,
    val transferSecret: String,
    val payloadSha256: String,
)

@Serializable
private data class RedeemRelayRequest(
    val relayId: String,
    val code: String,
    val payloadSha256: String,
)

@Serializable
private data class RedeemRelayResponse(
    val transferSecret: String,
)

@Serializable
private data class RelayError(
    val code: String = "invalid_request",
    val message: String = "迁移请求失败",
)

private suspend inline fun <reified T> HttpResponse.decoded(): T {
    if (status.isSuccess()) return body()
    val error =
        try {
            body<RelayError>()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    throw MigrationRelayApiException(
        errorCode = error?.code ?: "invalid_request",
        message = error?.message ?: "迁移请求失败，请检查网络后重试",
        status = status,
    )
}

private fun String.decodeTransferSecret(): ByteArray {
    val decoded =
        runCatching { Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL).decode(this) }
            .getOrElse { throw IllegalArgumentException("服务返回了无效的迁移密钥", it) }
    require(decoded.size == 32) { "服务返回了无效的迁移密钥" }
    return decoded
}
