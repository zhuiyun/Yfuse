package com.yfuse.core.account

import com.yfuse.core.logging.AppLog
import com.yfuse.core.network.embyHttpEngine
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

class AccountApiException(
    val code: String,
    override val message: String,
    val status: HttpStatusCode,
    val currentVersion: Long? = null,
) : Exception(message)

fun createAccountClient(engine: HttpClientEngine = embyHttpEngine()): HttpClient =
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
    }

class AccountApi(
    private val client: HttpClient,
    baseUrl: String = ACCOUNT_BASE_URL,
) {
    private val origin =
        baseUrl.trimEnd('/').also {
            require(it.startsWith("https://")) { "账号服务必须使用 HTTPS" }
        }

    suspend fun register(
        username: String,
        password: String,
        nickname: String?,
        avatarId: Int?,
        inviteCode: String? = null,
        deviceName: String? = null,
    ): AuthResponse =
        client
            .post("$origin/api/v1/auth/register") {
                contentType(ContentType.Application.Json)
                setBody(RegisterRequest(username, password, nickname, avatarId, inviteCode, deviceName))
            }.decoded()

    suspend fun login(
        username: String,
        password: String,
        deviceName: String? = null,
    ): AuthResponse =
        client
            .post("$origin/api/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody(LoginRequest(username, password, deviceName))
            }.decoded()

    suspend fun refresh(
        refreshToken: String,
        deviceName: String? = null,
        requestId: String? = null,
    ): AuthResponse {
        val response =
            client.post("$origin/api/v1/auth/refresh") {
                contentType(ContentType.Application.Json)
                setBody(RefreshRequest(refreshToken, deviceName, requestId))
            }
        if (response.status.isSuccess()) return response.body()
        val error = response.decodedError()
        if (!error.rejectsRefreshSchema() || (deviceName == null && requestId == null)) throw error
        // The APK and the account backend ship independently. A backend older than the client
        // decodes request bodies strictly and answers `invalid_json` to fields it does not know,
        // which read on the device as "登录失败" until the backend was redeployed. The body it does
        // know still refreshes the session; only the idempotency key and device label are lost.
        AppLog.warning(
            category = "account",
            event = "refresh_legacy_schema_fallback",
            message = "Account backend rejected the refresh request schema; retrying with the legacy body",
            attributes = mapOf("code" to error.code),
        )
        return try {
            val auth =
                client
                    .post("$origin/api/v1/auth/refresh") {
                        contentType(ContentType.Application.Json)
                        setBody(LegacyRefreshRequest(refreshToken))
                    }.decoded<AuthResponse>()
            AppLog.info(
                category = "account",
                event = "refresh_legacy_schema_result",
                message = "Account legacy refresh completed",
                attributes = mapOf("outcome" to "success"),
            )
            auth
        } catch (failure: Throwable) {
            // Never log the request body, rotating token, or raw HTTP exception message.
            AppLog.warning(
                category = "account",
                event = "refresh_legacy_schema_result",
                message = "Account legacy refresh did not complete",
                attributes =
                    mapOf(
                        "outcome" to "failed",
                        "exceptionType" to (failure::class.simpleName ?: "unknown"),
                        "status" to ((failure as? AccountApiException)?.status?.value?.toString() ?: "none"),
                    ),
            )
            throw failure
        }
    }

    suspend fun logout(accessToken: String) {
        client
            .post("$origin/api/v1/auth/logout") {
                bearerAuth(accessToken)
            }.decodedUnit()
    }

    suspend fun profile(accessToken: String): AccountUser =
        client
            .get("$origin/api/v1/account/profile") {
                bearerAuth(accessToken)
            }.decoded()

    suspend fun updateProfile(
        accessToken: String,
        nickname: String? = null,
        avatarId: Int? = null,
    ): AccountUser =
        client
            .put("$origin/api/v1/account/profile") {
                bearerAuth(accessToken)
                contentType(ContentType.Application.Json)
                setBody(UpdateProfileRequest(nickname, avatarId))
            }.decoded()

    suspend fun changePassword(
        accessToken: String,
        request: ChangePasswordRequest,
    ): AuthResponse =
        client
            .put("$origin/api/v1/account/password") {
                bearerAuth(accessToken)
                contentType(ContentType.Application.Json)
                setBody(request)
            }.decoded()

    suspend fun getSync(accessToken: String): SyncResponse =
        client
            .get("$origin/api/v1/account/sync") {
                bearerAuth(accessToken)
            }.decoded()

    suspend fun putSync(
        accessToken: String,
        baseVersion: Long,
        payload: EncryptedSyncPayload,
    ): SyncResponse =
        client
            .put("$origin/api/v1/account/sync") {
                bearerAuth(accessToken)
                contentType(ContentType.Application.Json)
                setBody(PutSyncRequest(baseVersion, payload))
            }.decoded()

    suspend fun clearSync(accessToken: String): SyncResponse =
        client
            .delete("$origin/api/v1/account/sync") {
                bearerAuth(accessToken)
            }.decoded()

    suspend fun sessions(accessToken: String): List<AccountDeviceSession> =
        client
            .get("$origin/api/v1/account/sessions") {
                bearerAuth(accessToken)
            }.decoded<AccountSessionsResponse>()
            .sessions

    suspend fun issueInvite(accessToken: String): IssuedInviteCode =
        client
            .post("$origin/api/v1/account/invites") {
                bearerAuth(accessToken)
            }.decoded()

    suspend fun revokeSession(
        accessToken: String,
        sessionId: String,
    ) {
        client
            .delete("$origin/api/v1/account/sessions/$sessionId") {
                bearerAuth(accessToken)
            }.decodedUnit()
    }

    suspend fun revokeOtherSessions(accessToken: String) {
        client
            .post("$origin/api/v1/account/sessions/revoke-others") {
                bearerAuth(accessToken)
            }.decodedUnit()
    }

    suspend fun revokeAllSessions(accessToken: String) {
        client
            .post("$origin/api/v1/account/sessions/revoke-all") {
                bearerAuth(accessToken)
            }.decodedUnit()
    }

    suspend fun exportAccount(accessToken: String): AccountExport =
        client
            .get("$origin/api/v1/account/export") {
                bearerAuth(accessToken)
            }.decoded()

    suspend fun deleteAccount(
        accessToken: String,
        password: String,
    ) {
        client
            .delete("$origin/api/v1/account") {
                bearerAuth(accessToken)
                contentType(ContentType.Application.Json)
                setBody(DeleteAccountRequest(password))
            }.decodedUnit()
    }
}

private suspend inline fun <reified T> HttpResponse.decoded(): T {
    if (status.isSuccess()) return body()
    throw decodedError()
}

private suspend fun HttpResponse.decodedUnit() {
    if (status.isSuccess()) return
    throw decodedError()
}

/** A well-formed body the backend still could not decode: the request schema, not the JSON. */
private fun AccountApiException.rejectsRefreshSchema(): Boolean =
    status == HttpStatusCode.BadRequest && code == "invalid_json"

private suspend fun HttpResponse.decodedError(): AccountApiException {
    val envelope = runCatching { body<ErrorEnvelope>() }.getOrNull()
    return AccountApiException(
        code = envelope?.error?.code ?: "http_${status.value}",
        message = envelope?.error?.message ?: "账号服务暂时不可用",
        status = status,
        currentVersion = envelope?.error?.currentVersion,
    )
}
