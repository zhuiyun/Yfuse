package com.yfuse.core.migration

import com.yfuse.core.account.ACCOUNT_BASE_URL
import com.yfuse.core.network.embyHttpEngine
import com.yfuse.core.security.base64UrlToBytes
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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
    private val client: HttpClient = createMigrationRelayClient(),
    baseUrl: String = ACCOUNT_BASE_URL,
) {
    private val origin =
        baseUrl.trimEnd('/').also {
            require(it.startsWith("https://")) { "迁移服务必须使用 HTTPS" }
        }

    suspend fun create(
        relayId: String,
        transferSecret: String,
        payloadSha256: String,
    ): MigrationRelayTicket =
        client
            .post("$origin/api/v1/migration-relays") {
                contentType(ContentType.Application.Json)
                setBody(CreateRelayRequest(relayId, transferSecret, payloadSha256))
            }.decoded()

    /** A successful redemption consumes the key even if later local decryption fails. */
    suspend fun redeem(
        relayId: String,
        code: String,
        payloadSha256: String,
    ): ByteArray {
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
    engine: HttpClientEngine = embyHttpEngine(),
    trustedOrigin: String = ACCOUNT_BASE_URL,
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
    val error = runCatching { body<RelayError>() }.getOrNull()
    throw MigrationRelayApiException(
        errorCode = error?.code ?: "invalid_request",
        message = error?.message ?: "迁移请求失败，请检查网络后重试",
        status = status,
    )
}

private fun String.decodeTransferSecret(): ByteArray {
    val decoded =
        runCatching { base64UrlToBytes() }
            .getOrElse { throw IllegalArgumentException("服务返回了无效的迁移密钥", it) }
    require(decoded.size == 32) { "服务返回了无效的迁移密钥" }
    return decoded
}
