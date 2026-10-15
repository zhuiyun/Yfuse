package com.yfuse.watch

import com.yfuse.watch.account.AccountProblem
import com.yfuse.watch.account.AccountServiceException
import com.yfuse.watch.account.AccountWorkRejectedException
import com.yfuse.watch.account.AuthenticatedAccount
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.sql.SQLTransientException
import java.util.concurrent.ThreadLocalRandom

private const val ACCOUNT_AUTH_RETRY_BASE_MS = 100L
private const val ACCOUNT_AUTH_RETRY_MAX_MS = 5_000L
private const val ACCOUNT_AUTH_RETRY_MAX_EXPONENT = 6
private const val ACCOUNT_AUTH_ATTEMPT_TIMEOUT_MS = 10_000L
private const val ACCOUNT_INITIAL_AUTH_MAX_TRANSIENT_FAILURES = 8
internal const val WATCH_INITIAL_AUTH_TIMEOUT_MS = 15_000L

private sealed interface WatchAccountAuthentication {
    data class Accepted(
        val account: AuthenticatedAccount,
    ) : WatchAccountAuthentication

    data object Rejected : WatchAccountAuthentication

    data object TemporarilyUnavailable : WatchAccountAuthentication

    data object Failed : WatchAccountAuthentication
}

private suspend fun authenticateWatchAccount(
    authenticator: suspend (String) -> AuthenticatedAccount,
    accessToken: String,
): WatchAccountAuthentication =
    try {
        withTimeoutOrNull(ACCOUNT_AUTH_ATTEMPT_TIMEOUT_MS) {
            WatchAccountAuthentication.Accepted(authenticator(accessToken))
        } ?: WatchAccountAuthentication.TemporarilyUnavailable
    } catch (failure: AccountServiceException) {
        if (failure.problem == AccountProblem.Unauthorized) {
            WatchAccountAuthentication.Rejected
        } else {
            WatchAccountAuthentication.Failed
        }
    } catch (_: AccountWorkRejectedException) {
        WatchAccountAuthentication.TemporarilyUnavailable
    } catch (_: SQLTransientException) {
        WatchAccountAuthentication.TemporarilyUnavailable
    } catch (failure: CancellationException) {
        throw failure
    } catch (_: Exception) {
        WatchAccountAuthentication.Failed
    }

private fun nextWatchAuthFailureCount(current: Int): Int =
    (current + 1).coerceAtMost(ACCOUNT_AUTH_RETRY_MAX_EXPONENT + 1)

/** Full-jitter exponential retry, bounded so an account outage cannot create a retry storm. */
internal fun watchAuthTransientRetryDelayMs(failureCount: Int): Long {
    require(failureCount > 0) { "failureCount must be positive" }
    val exponent = (failureCount - 1).coerceAtMost(ACCOUNT_AUTH_RETRY_MAX_EXPONENT)
    val ceiling =
        (ACCOUNT_AUTH_RETRY_BASE_MS shl exponent)
            .coerceAtMost(ACCOUNT_AUTH_RETRY_MAX_MS)
    val floor = (ceiling / 2L).coerceAtLeast(1L)
    return ThreadLocalRandom.current().nextLong(floor, ceiling + 1L)
}

/** Authenticates this socket before it can bind room membership or start revocation checks. */
internal suspend fun DefaultWebSocketServerSession.authenticateWatchConnection(
    accessToken: String,
    authenticator: suspend (String) -> AuthenticatedAccount,
    retryDelayMs: (Int) -> Long,
    totalTimeoutMs: Long = WATCH_INITIAL_AUTH_TIMEOUT_MS,
): AuthenticatedAccount? {
    require(totalTimeoutMs > 0L)
    var account: AuthenticatedAccount? = null
    val completed =
        withTimeoutOrNull(totalTimeoutMs) {
            account = authenticateWatchConnectionWithRetries(accessToken, authenticator, retryDelayMs)
            true
        } ?: false
    if (!completed) {
        close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "account_auth_timeout"))
        // Timeout can win while the block is returning an already resolved account.
        return null
    }
    return account
}

private suspend fun DefaultWebSocketServerSession.authenticateWatchConnectionWithRetries(
    accessToken: String,
    authenticator: suspend (String) -> AuthenticatedAccount,
    retryDelayMs: (Int) -> Long,
): AuthenticatedAccount? {
    var transientFailures = 0
    var acceptedAccount: AuthenticatedAccount? = null
    while (acceptedAccount == null) {
        when (
            val authentication =
                authenticateWatchAccount(
                    authenticator,
                    accessToken,
                )
        ) {
            is WatchAccountAuthentication.Accepted -> {
                acceptedAccount = authentication.account
            }
            WatchAccountAuthentication.Rejected -> {
                WatchMetrics.authFailures.incrementAndGet()
                close(
                    CloseReason(
                        CloseReason.Codes.VIOLATED_POLICY,
                        "account_auth_expired",
                    ),
                )
                return null
            }
            WatchAccountAuthentication.TemporarilyUnavailable -> {
                transientFailures++
                if (transientFailures >= ACCOUNT_INITIAL_AUTH_MAX_TRANSIENT_FAILURES) {
                    close(
                        CloseReason(
                            CloseReason.Codes.TRY_AGAIN_LATER,
                            "account_auth_temporarily_unavailable",
                        ),
                    )
                    return null
                }
                delay(retryDelayMs(transientFailures).coerceAtLeast(1L))
            }
            WatchAccountAuthentication.Failed -> {
                close(
                    CloseReason(
                        CloseReason.Codes.INTERNAL_ERROR,
                        "account_auth_unavailable",
                    ),
                )
                return null
            }
        }
    }
    return checkNotNull(acceptedAccount)
}

/** Child of the socket scope; cleanup still cancels and joins it before releasing membership. */
internal fun DefaultWebSocketServerSession.launchWatchAccountRevalidation(
    account: AuthenticatedAccount,
    accessToken: String,
    revalidator: suspend (String) -> AuthenticatedAccount,
    revalidationMs: Long,
    retryDelayMs: (Int) -> Long,
    clock: () -> Long,
): Job =
    launch {
        var transientFailures = 0
        while (true) {
            val untilExpiry =
                account.accessExpiresAtEpochMs -
                    clock()
            if (untilExpiry <= 0L) {
                close(
                    CloseReason(
                        CloseReason.Codes.VIOLATED_POLICY,
                        "account_auth_expired",
                    ),
                )
                break
            }
            val delayMs =
                if (transientFailures == 0) {
                    revalidationMs
                } else {
                    retryDelayMs(transientFailures).coerceAtLeast(1L)
                }
            delay(minOf(delayMs, untilExpiry))
            if (clock() >= account.accessExpiresAtEpochMs) {
                close(
                    CloseReason(
                        CloseReason.Codes.VIOLATED_POLICY,
                        "account_auth_expired",
                    ),
                )
                break
            }
            when (
                val authentication =
                    authenticateWatchAccount(
                        revalidator,
                        accessToken,
                    )
            ) {
                is WatchAccountAuthentication.Accepted -> {
                    if (
                        authentication.account.sessionId !=
                        account.sessionId ||
                        authentication.account.userId != account.userId
                    ) {
                        close(
                            CloseReason(
                                CloseReason.Codes.VIOLATED_POLICY,
                                "account_auth_expired",
                            ),
                        )
                        break
                    }
                    transientFailures = 0
                }
                WatchAccountAuthentication.Rejected -> {
                    close(
                        CloseReason(
                            CloseReason.Codes.VIOLATED_POLICY,
                            "account_auth_expired",
                        ),
                    )
                    break
                }
                WatchAccountAuthentication.TemporarilyUnavailable -> {
                    transientFailures = nextWatchAuthFailureCount(transientFailures)
                }
                WatchAccountAuthentication.Failed -> {
                    close(
                        CloseReason(
                            CloseReason.Codes.INTERNAL_ERROR,
                            "account_auth_unavailable",
                        ),
                    )
                    break
                }
            }
        }
    }
