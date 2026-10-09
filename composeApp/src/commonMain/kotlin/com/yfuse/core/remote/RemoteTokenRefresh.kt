package com.yfuse.core.remote

import com.yfuse.core.account.AccountApiException
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException

/** A temporary account-service outage must not terminate the remote host's lifecycle observer. */
internal enum class RemoteTokenRefresh {
    Renewed,
    Expired,
    Unavailable,
}

internal suspend fun refreshRemoteToken(refresh: suspend () -> String?): RemoteTokenRefresh =
    try {
        if (refresh() != null) RemoteTokenRefresh.Renewed else RemoteTokenRefresh.Expired
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: AccountApiException) {
        when (failure.status) {
            HttpStatusCode.Unauthorized -> RemoteTokenRefresh.Expired
            else -> RemoteTokenRefresh.Unavailable
        }
    } catch (_: Exception) {
        RemoteTokenRefresh.Unavailable
    }
