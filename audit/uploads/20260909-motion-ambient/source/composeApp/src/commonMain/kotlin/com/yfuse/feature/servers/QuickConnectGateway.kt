package com.yfuse.feature.servers

import com.yfuse.core.data.AuthedServer

/** Server-issued Quick Connect session. The code is displayed, never treated as a token. */
data class QuickConnectSession(
    val id: String,
    val code: String,
    val expiresAtEpochMs: Long,
)

sealed interface QuickConnectStartResult {
    data class AwaitingApproval(
        val session: QuickConnectSession,
    ) : QuickConnectStartResult

    data class Unsupported(
        val reason: String = QuickConnectUnsupportedMessage,
    ) : QuickConnectStartResult
}

sealed interface QuickConnectPollResult {
    data object Pending : QuickConnectPollResult

    data class Authenticated(
        val server: AuthedServer,
    ) : QuickConnectPollResult

    data object Expired : QuickConnectPollResult

    data class Rejected(
        val reason: String,
    ) : QuickConnectPollResult
}

/**
 * Integration seam for an Emby Quick Connect implementation.
 *
 * The current repository has no Quick Connect endpoints or DTOs, so production defaults to
 * [UnsupportedQuickConnectGateway]. A future implementation must return a real [AuthedServer]
 * from the server before the store persists anything; a displayed code can never fake success.
 */
interface QuickConnectGateway {
    suspend fun start(baseUrl: String): Result<QuickConnectStartResult>

    suspend fun poll(
        baseUrl: String,
        sessionId: String,
    ): Result<QuickConnectPollResult>

    suspend fun cancel(
        baseUrl: String,
        sessionId: String,
    ): Result<Unit>
}

object UnsupportedQuickConnectGateway : QuickConnectGateway {
    override suspend fun start(baseUrl: String): Result<QuickConnectStartResult> =
        Result.success(QuickConnectStartResult.Unsupported())

    override suspend fun poll(
        baseUrl: String,
        sessionId: String,
    ): Result<QuickConnectPollResult> = Result.success(QuickConnectPollResult.Rejected(QuickConnectUnsupportedMessage))

    override suspend fun cancel(
        baseUrl: String,
        sessionId: String,
    ): Result<Unit> = Result.success(Unit)
}

const val QuickConnectUnsupportedMessage =
    "此服务器未启用或不支持 Quick Connect，请使用用户名和密码登录"
