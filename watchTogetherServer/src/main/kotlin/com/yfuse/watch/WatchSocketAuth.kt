package com.yfuse.watch

import com.yfuse.watch.account.AccountProblem
import com.yfuse.watch.account.AccountServiceException
import com.yfuse.watch.account.AccountWorkRejectedException
import com.yfuse.watch.account.AuthenticatedAccount
import com.yfuse.watch.protocol.WatchProtocol
import com.yfuse.watch.protocol.WatchWireMessage
import io.ktor.websocket.CloseReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.sql.SQLTransientException

/**
 * How long a socket that can renew its access waits for a fresh token after the relay finds the
 * one it holds revoked or replaced. A refresh rotates the session's access token, and the socket's
 * owner may have refreshed elsewhere a moment before telling this socket.
 */
internal const val WATCH_REAUTH_RENEWAL_GRACE_MS = 10_000L

/** `reauthenticate` attempts per socket and minute; each one is an account-store lookup. */
private const val MAX_REAUTH_ATTEMPTS_PER_MINUTE = 6
private const val REAUTH_WINDOW_MS = 60_000L

internal sealed interface WatchAccountAuthentication {
    data class Accepted(
        val account: AuthenticatedAccount,
    ) : WatchAccountAuthentication

    data object Rejected : WatchAccountAuthentication

    data object TemporarilyUnavailable : WatchAccountAuthentication

    data object Failed : WatchAccountAuthentication
}

internal suspend fun authenticateWatchAccount(
    authenticator: suspend (String) -> AuthenticatedAccount,
    accessToken: String,
): WatchAccountAuthentication =
    try {
        WatchAccountAuthentication.Accepted(
            withTimeout(ACCOUNT_AUTH_ATTEMPT_TIMEOUT_MS) {
                authenticator(accessToken)
            },
        )
    } catch (failure: AccountServiceException) {
        if (failure.problem == AccountProblem.Unauthorized) {
            WatchAccountAuthentication.Rejected
        } else {
            WatchAccountAuthentication.Failed
        }
    } catch (_: AccountWorkRejectedException) {
        WatchAccountAuthentication.TemporarilyUnavailable
    } catch (_: TimeoutCancellationException) {
        WatchAccountAuthentication.TemporarilyUnavailable
    } catch (_: SQLTransientException) {
        WatchAccountAuthentication.TemporarilyUnavailable
    } catch (failure: CancellationException) {
        throw failure
    } catch (_: Exception) {
        WatchAccountAuthentication.Failed
    }

internal fun nextWatchAuthFailureCount(current: Int): Int =
    (current + 1).coerceAtMost(
        ACCOUNT_AUTH_RETRY_MAX_EXPONENT + 1,
    )

/**
 * One socket's account access: the token it currently holds, when that lapses, and whether the
 * client can renew it in-band.
 *
 * Access tokens last minutes; watch and 手机遥控 sockets last hours. Closing a socket when its
 * token lapsed (or when a refresh elsewhere replaced it) cut a television's remote and a room's
 * member off every quarter hour. A client that declares [WatchProtocol.CAPABILITY_REAUTHENTICATE]
 * sends a fresh token of the same account session instead, and the socket carries on with it. A
 * client that does not is treated exactly as before.
 */
internal class WatchSocketAuth(
    initialToken: String,
    private val account: AuthenticatedAccount,
    private val revalidator: suspend (String) -> AuthenticatedAccount,
    private val retryDelayMs: (Int) -> Long,
    private val clock: () -> Long,
    private val revalidationMs: Long,
    private val renewalGraceMs: Long = WATCH_REAUTH_RENEWAL_GRACE_MS,
) {
    private val token = MutableStateFlow(initialToken)
    private val recentAttemptsAtMs = ArrayDeque<Long>()

    @Volatile
    var expiresAtMs: Long = account.accessExpiresAtEpochMs
        private set

    @Volatile
    var renewable: Boolean = false
        private set

    /**
     * The client said it can renew. Returns the first `reauthenticated` — when its access lapses —
     * to send once; null when it had already said so.
     */
    @Synchronized
    fun declareRenewable(): WatchWireMessage? {
        if (renewable) return null
        renewable = true
        return renewedMessage()
    }

    /**
     * `reauthenticate` with [candidate]: accepted only for the same account and the same session
     * the socket opened with, and only while that token is valid. The answer goes back to the
     * client either way.
     */
    suspend fun reauthenticate(candidate: String?): WatchWireMessage {
        if (!WatchProtocol.isValidAccessToken(candidate)) return failure("登录凭据无效", "reauth_invalid")
        if (!admitAttempt()) return failure("登录续期过于频繁，请稍后再试", "reauth_rate_limited")
        return when (val result = authenticateWatchAccount(revalidator, candidate!!)) {
            is WatchAccountAuthentication.Accepted -> {
                val renewed = result.account
                when {
                    renewed.userId != account.userId || renewed.sessionId != account.sessionId ->
                        failure("登录凭据不属于当前会话", "reauth_mismatch")
                    renewed.accessExpiresAtEpochMs <= clock() -> failure("登录凭据已过期", "reauth_rejected")
                    else -> {
                        synchronized(this) {
                            renewable = true
                            expiresAtMs = renewed.accessExpiresAtEpochMs
                            token.value = candidate
                        }
                        renewedMessage()
                    }
                }
            }
            WatchAccountAuthentication.Rejected -> failure("登录凭据无效或已过期", "reauth_rejected")
            WatchAccountAuthentication.TemporarilyUnavailable,
            WatchAccountAuthentication.Failed,
            -> failure("账号服务暂时不可用，请稍后再试", "reauth_unavailable")
        }
    }

    /**
     * Revalidates the current token every [revalidationMs] until the socket has to close, and
     * returns why. A lapsed token closes at once, as it always did. A revoked or replaced one
     * closes at once too, unless the client can renew: it is then told so ([notify]) and given
     * [renewalGraceMs] — never past the expiry — to send a fresh token.
     */
    suspend fun watch(notify: suspend (WatchWireMessage) -> Unit): CloseReason {
        var transientFailures = 0
        while (true) {
            val untilExpiry = expiresAtMs - clock()
            if (untilExpiry <= 0L) return expired()
            val delayMs =
                if (transientFailures == 0) {
                    revalidationMs
                } else {
                    retryDelayMs(transientFailures).coerceAtLeast(1L)
                }
            delay(minOf(delayMs, untilExpiry))
            if (clock() >= expiresAtMs) return expired()
            val checked = token.value
            when (val authentication = authenticateWatchAccount(revalidator, checked)) {
                is WatchAccountAuthentication.Accepted -> {
                    if (
                        authentication.account.sessionId != account.sessionId ||
                        authentication.account.userId != account.userId
                    ) {
                        return expired()
                    }
                    transientFailures = 0
                }
                WatchAccountAuthentication.Rejected -> {
                    // A renewal that landed while this check ran is checked on the next round.
                    if (token.value != checked) continue
                    if (!renewable) return expired()
                    runCatching { notify(failure("登录状态需要续期", "reauth_required")) }
                    val graceMs = minOf(renewalGraceMs, expiresAtMs - clock())
                    val renewed =
                        graceMs > 0L &&
                            withTimeoutOrNull(graceMs) { token.first { it != checked } } != null
                    if (!renewed) return expired()
                    transientFailures = 0
                }
                WatchAccountAuthentication.TemporarilyUnavailable -> {
                    transientFailures = nextWatchAuthFailureCount(transientFailures)
                }
                WatchAccountAuthentication.Failed ->
                    return CloseReason(CloseReason.Codes.INTERNAL_ERROR, "account_auth_unavailable")
            }
        }
    }

    private fun renewedMessage(): WatchWireMessage =
        WatchWireMessage(type = "reauthenticated", authExpiresAtMs = expiresAtMs)

    @Synchronized
    private fun admitAttempt(): Boolean {
        val now = monotonicMs()
        while (recentAttemptsAtMs.isNotEmpty() && now - recentAttemptsAtMs.first() >= REAUTH_WINDOW_MS) {
            recentAttemptsAtMs.removeFirst()
        }
        if (recentAttemptsAtMs.size >= MAX_REAUTH_ATTEMPTS_PER_MINUTE) return false
        recentAttemptsAtMs.addLast(now)
        return true
    }

    private fun expired(): CloseReason = CloseReason(CloseReason.Codes.VIOLATED_POLICY, "account_auth_expired")

    private fun failure(
        message: String,
        code: String,
    ): WatchWireMessage =
        WatchWireMessage(
            type = "error",
            message = message,
            errorCode = code,
            retryable = WatchProtocol.isRetryableErrorCode(code),
        )
}
