package com.yfuse.watch.account

import com.yfuse.watch.registerAccountHealthDependency
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.log
import io.ktor.server.plugins.origin
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream

internal fun Route.accountRoutes(
    backend: AccountBackend,
    rateLimiter: AccountRateLimiter,
) {
    registerAccountHealthDependency(backend)
    route("/api/v1") {
        route("/auth") {
            post("/register") {
                call.handleAccountEndpoint(rateLimiter, AccountRateLimitBucket.Credentials) {
                    val request = call.receiveLimitedJson<RegisterRequest>()
                    val response = backend.execute { register(request) }
                    call.respondLimitedJson(response, HttpStatusCode.Created)
                }
            }
            post("/login") {
                call.handleAccountEndpoint(rateLimiter, AccountRateLimitBucket.Credentials) {
                    val request = call.receiveLimitedJson<LoginRequest>()
                    val response = backend.execute { login(request) }
                    call.respondLimitedJson(response)
                }
            }
            post("/refresh") {
                call.handleAccountEndpoint(rateLimiter, AccountRateLimitBucket.Refresh) {
                    val request = call.receiveLimitedJson<RefreshRequest>()
                    val response = backend.execute { refresh(request) }
                    call.respondLimitedJson(response)
                }
            }
            post("/logout") {
                call.handleAccountEndpoint(rateLimiter, AccountRateLimitBucket.Logout) {
                    val accessToken = call.requireBearerToken()
                    backend.execute { logout(accessToken) }
                    call.respond(HttpStatusCode.NoContent)
                }
            }
        }

        route("/account") {
            get("/profile") {
                call.handleAccountEndpoint(rateLimiter, AccountRateLimitBucket.ProfileRead) {
                    val accessToken = call.requireBearerToken()
                    call.respondLimitedJson(backend.execute { getProfile(accessToken) })
                }
            }
            post("/invites") {
                call.handleAccountEndpoint(rateLimiter, AccountRateLimitBucket.InviteIssue) {
                    val accessToken = call.requireBearerToken()
                    val response = backend.execute { issueInvite(accessToken) }
                    call.respondLimitedJson(response, HttpStatusCode.Created)
                }
            }
            get("/sessions") {
                call.handleAccountEndpoint(rateLimiter, AccountRateLimitBucket.ProfileRead) {
                    val accessToken = call.requireBearerToken()
                    call.respondLimitedJson(backend.execute { listSessions(accessToken) })
                }
            }
            delete("/sessions/{sessionId}") {
                call.handleAccountEndpoint(rateLimiter, AccountRateLimitBucket.ProfileWrite) {
                    val accessToken = call.requireBearerToken()
                    val sessionId = call.parameters["sessionId"].orEmpty()
                    backend.execute { revokeSession(accessToken, sessionId) }
                    call.respond(HttpStatusCode.NoContent)
                }
            }
            post("/sessions/revoke-others") {
                call.handleAccountEndpoint(rateLimiter, AccountRateLimitBucket.ProfileWrite) {
                    val accessToken = call.requireBearerToken()
                    backend.execute { revokeOtherSessions(accessToken) }
                    call.respond(HttpStatusCode.NoContent)
                }
            }
            post("/sessions/revoke-all") {
                call.handleAccountEndpoint(rateLimiter, AccountRateLimitBucket.ProfileWrite) {
                    val accessToken = call.requireBearerToken()
                    backend.execute { revokeAllSessions(accessToken) }
                    call.respond(HttpStatusCode.NoContent)
                }
            }
            get("/export") {
                call.handleAccountEndpoint(rateLimiter, AccountRateLimitBucket.ProfileRead) {
                    val accessToken = call.requireBearerToken()
                    call.respondLimitedJson(backend.execute { exportAccount(accessToken) })
                }
            }
            delete {
                call.handleAccountEndpoint(rateLimiter, AccountRateLimitBucket.PasswordChange) {
                    val accessToken = call.requireBearerToken()
                    val request = call.receiveLimitedJson<DeleteAccountRequest>()
                    backend.execute { deleteAccount(accessToken, request) }
                    call.respond(HttpStatusCode.NoContent)
                }
            }
            put("/profile") {
                call.handleAccountEndpoint(rateLimiter, AccountRateLimitBucket.ProfileWrite) {
                    val accessToken = call.requireBearerToken()
                    val request = call.receiveLimitedJson<UpdateProfileRequest>()
                    val response = backend.execute { updateProfile(accessToken, request) }
                    call.respondLimitedJson(response)
                }
            }
            put("/password") {
                call.handleAccountEndpoint(rateLimiter, AccountRateLimitBucket.PasswordChange) {
                    val accessToken = call.requireBearerToken()
                    val request = call.receiveLimitedJson<ChangePasswordRequest>()
                    val response = backend.execute { changePassword(accessToken, request) }
                    call.respondLimitedJson(response)
                }
            }
            get("/sync") {
                call.handleAccountEndpoint(rateLimiter, AccountRateLimitBucket.SyncRead) {
                    val accessToken = call.requireBearerToken()
                    call.respondLimitedJson(backend.execute { getSync(accessToken) })
                }
            }
            put("/sync") {
                call.handleAccountEndpoint(rateLimiter, AccountRateLimitBucket.SyncWrite) {
                    val accessToken = call.requireBearerToken()
                    val request = call.receiveLimitedJson<PutSyncRequest>()
                    val response = backend.execute { putSync(accessToken, request) }
                    call.respondLimitedJson(response)
                }
            }
            delete("/sync") {
                call.handleAccountEndpoint(rateLimiter, AccountRateLimitBucket.SyncWrite) {
                    val accessToken = call.requireBearerToken()
                    call.respondLimitedJson(backend.execute { deleteSync(accessToken) })
                }
            }
            get("/playback") {
                call.handleAccountEndpoint(rateLimiter, AccountRateLimitBucket.SyncRead) {
                    val accessToken = call.requireBearerToken()
                    val account = backend.authenticateAccessToken(accessToken)
                    val after = call.request.queryParameters["after"]?.toLongOrNull() ?: 0L
                    val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 100
                    if (after < 0L || limit !in 1..200) {
                        throw AccountServiceException(
                            AccountProblem.InvalidRequest,
                            "playback_query_invalid",
                            "播放记录同步参数无效",
                        )
                    }
                    call.respondLimitedJson(
                        PlaybackRelayStoreProvider.instance.pull(account.userId, after, limit),
                    )
                }
            }
            post("/playback") {
                call.handleAccountEndpoint(rateLimiter, AccountRateLimitBucket.SyncWrite) {
                    val accessToken = call.requireBearerToken()
                    val account = backend.authenticateAccessToken(accessToken)
                    val request = call.receiveLimitedJson<PlaybackPushRequest>()
                    val response =
                        try {
                            PlaybackRelayStoreProvider.instance.push(
                                userId = account.userId,
                                request = request,
                                nowEpochMs = System.currentTimeMillis(),
                            )
                        } catch (_: IllegalArgumentException) {
                            throw AccountServiceException(
                                AccountProblem.InvalidRequest,
                                "playback_payload_invalid",
                                "播放记录同步数据无效",
                            )
                        }
                    call.respondLimitedJson(response)
                }
            }
        }
    }
}

private suspend fun ApplicationCall.handleAccountEndpoint(
    rateLimiter: AccountRateLimiter? = null,
    rateLimitBucket: AccountRateLimitBucket? = null,
    block: suspend () -> Unit,
) {
    response.headers.append(HttpHeaders.CacheControl, "no-store")
    response.headers.append(HttpHeaders.Pragma, "no-cache")
    try {
        requireSecureAccountTransport()
        if (rateLimiter != null && rateLimitBucket != null) {
            val clientIdentity =
                when (
                    val result =
                        resolveAccountClientIdentity(
                            remoteHost = request.origin.remoteHost,
                            forwardedForValues = request.headers.getAll("X-Forwarded-For"),
                        )
                ) {
                    is ClientIdentityResolution.Resolved -> result.value
                    ClientIdentityResolution.InvalidForwardedFor -> throw InvalidForwardedForException()
                }
            when (val decision = rateLimiter.check(clientIdentity, rateLimitBucket)) {
                RateLimitDecision.Allowed -> Unit
                is RateLimitDecision.Limited -> throw RateLimitedException(decision.retryAfterSeconds)
            }
        }
        block()
    } catch (failure: AccountServiceException) {
        val status =
            when (failure.problem) {
                AccountProblem.InvalidRequest -> HttpStatusCode.BadRequest
                AccountProblem.InvalidCredentials,
                AccountProblem.Unauthorized,
                -> HttpStatusCode.Unauthorized
                AccountProblem.UsernameUnavailable,
                AccountProblem.VersionConflict,
                AccountProblem.NonceReused,
                -> HttpStatusCode.Conflict
                AccountProblem.RateLimited -> HttpStatusCode.TooManyRequests
                AccountProblem.RegistrationClosed -> HttpStatusCode.ServiceUnavailable
                AccountProblem.InvitationInvalid -> HttpStatusCode.Forbidden
                AccountProblem.CurrentPasswordInvalid -> HttpStatusCode.Forbidden
                AccountProblem.Forbidden -> HttpStatusCode.Forbidden
            }
        if (status == HttpStatusCode.Unauthorized) {
            response.headers.append(HttpHeaders.WWWAuthenticate, "Bearer")
        }
        failure.retryAfterSeconds?.let {
            response.headers.append(HttpHeaders.RetryAfter, it.toString())
        }
        respondError(status, failure.safeCode, failure.safeMessage, failure.currentVersion)
    } catch (_: RequestTooLargeException) {
        respondError(HttpStatusCode.PayloadTooLarge, "request_too_large", "请求内容过大")
    } catch (_: UnsupportedMediaTypeException) {
        respondError(
            HttpStatusCode.UnsupportedMediaType,
            "content_type_invalid",
            "请求 Content-Type 必须为 application/json",
        )
    } catch (_: HttpsRequiredException) {
        respondError(
            HttpStatusCode.UpgradeRequired,
            "https_required",
            "账号接口仅接受 HTTPS 请求",
        )
    } catch (_: InvalidForwardedForException) {
        respondError(
            HttpStatusCode.BadRequest,
            "forwarded_for_invalid",
            "代理客户端地址无效",
        )
    } catch (failure: RateLimitedException) {
        response.headers.append(HttpHeaders.RetryAfter, failure.retryAfterSeconds.toString())
        respondError(
            HttpStatusCode.TooManyRequests,
            "rate_limited",
            "尝试次数过多，请稍后再试",
        )
    } catch (_: ResponseTooLargeException) {
        application.log.error("Account API response exceeded its configured limit")
        respondError(
            HttpStatusCode.InternalServerError,
            "response_too_large",
            "服务器无法生成响应",
        )
    } catch (_: AccountWorkRejectedException) {
        response.headers.append(HttpHeaders.RetryAfter, "1")
        respondError(
            HttpStatusCode.ServiceUnavailable,
            "account_busy",
            "账号服务繁忙，请稍后重试",
        )
    } catch (_: SerializationException) {
        respondError(HttpStatusCode.BadRequest, "invalid_json", "JSON 请求格式无效")
    } catch (failure: CancellationException) {
        throw failure
    } catch (failure: Throwable) {
        // Never include exception messages or request objects here: JDBC/JSON failures must
        // not accidentally put password or token material into logs or responses.
        application.log.error("Account API request failed (${failure::class.simpleName})")
        respondError(HttpStatusCode.InternalServerError, "internal_error", "服务器暂时无法处理请求")
    }
}

private suspend inline fun <reified T> ApplicationCall.receiveLimitedJson(
    maxBytes: Int = AccountLimits.MAX_REQUEST_BYTES,
): T {
    if (!request.contentType().match(ContentType.Application.Json)) {
        throw UnsupportedMediaTypeException()
    }
    request.headers[HttpHeaders.ContentLength]?.let { rawLength ->
        val length =
            rawLength.toLongOrNull()
                ?: throw AccountServiceException(
                    AccountProblem.InvalidRequest,
                    "content_length_invalid",
                    "Content-Length 无效",
                )
        if (length > maxBytes) throw RequestTooLargeException()
        if (length < 0L) {
            throw AccountServiceException(
                AccountProblem.InvalidRequest,
                "content_length_invalid",
                "Content-Length 无效",
            )
        }
    }

    val channel = receiveChannel()
    val output = ByteArrayOutputStream(minOf(maxBytes, 8 * 1024))
    val chunk = ByteArray(8 * 1024)
    var total = 0
    while (true) {
        val read = channel.readAvailable(chunk)
        if (read == -1) break
        if (read == 0) continue
        total += read
        if (total > maxBytes) throw RequestTooLargeException()
        output.write(chunk, 0, read)
    }
    if (total == 0) {
        throw AccountServiceException(
            AccountProblem.InvalidRequest,
            "request_empty",
            "请求内容不能为空",
        )
    }
    val bytes = output.toByteArray()
    val text = bytes.toString(Charsets.UTF_8)
    if (!text.toByteArray(Charsets.UTF_8).contentEquals(bytes)) {
        throw SerializationException("Request is not valid UTF-8")
    }
    return apiJson.decodeFromString(text)
}

private suspend inline fun <reified T> ApplicationCall.respondLimitedJson(
    value: T,
    status: HttpStatusCode = HttpStatusCode.OK,
) {
    val body = apiJson.encodeToString(value)
    if (body.toByteArray(Charsets.UTF_8).size > AccountLimits.MAX_RESPONSE_BYTES) {
        throw ResponseTooLargeException()
    }
    respondText(body, ContentType.Application.Json, status)
}

private suspend fun ApplicationCall.respondError(
    status: HttpStatusCode,
    code: String,
    message: String,
    currentVersion: Long? = null,
) {
    respondLimitedJson(
        ApiErrorResponse(ApiError(code, message, currentVersion)),
        status,
    )
}

private fun ApplicationCall.requireBearerToken(): String {
    val authorization =
        request.headers[HttpHeaders.Authorization]
            ?: throw unauthorizedException()
    if (authorization.length > MAX_AUTHORIZATION_HEADER_BYTES) throw unauthorizedException()
    val prefix = "Bearer "
    if (!authorization.startsWith(prefix, ignoreCase = true)) throw unauthorizedException()
    val token = authorization.substring(prefix.length)
    if (token.isBlank() || token.any(Char::isWhitespace)) throw unauthorizedException()
    return token
}

private fun ApplicationCall.requireSecureAccountTransport() {
    if (
        !isSecureAccountTransport(
            directScheme = request.origin.scheme,
            remoteHost = request.origin.remoteHost,
            forwardedProto = request.headers["X-Forwarded-Proto"],
        )
    ) {
        throw HttpsRequiredException()
    }
}

internal fun isSecureAccountTransport(
    directScheme: String,
    remoteHost: String,
    forwardedProto: String?,
): Boolean {
    if (directScheme.equals("https", ignoreCase = true)) return true
    if (!isLoopbackHost(remoteHost)) return false
    return forwardedProto?.trim()?.equals("https", ignoreCase = true) == true
}

private fun unauthorizedException(): AccountServiceException =
    AccountServiceException(
        problem = AccountProblem.Unauthorized,
        safeCode = "unauthorized",
        safeMessage = "登录状态无效或已过期",
    )

private class RequestTooLargeException : RuntimeException()

private class UnsupportedMediaTypeException : RuntimeException()

private class HttpsRequiredException : RuntimeException()

private class InvalidForwardedForException : RuntimeException()

private class RateLimitedException(
    val retryAfterSeconds: Long,
) : RuntimeException()

private class ResponseTooLargeException : RuntimeException()

private const val MAX_AUTHORIZATION_HEADER_BYTES = 256

private val apiJson =
    Json {
        ignoreUnknownKeys = false
        encodeDefaults = false
        explicitNulls = false
    }
