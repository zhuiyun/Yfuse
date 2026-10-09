package com.yfuse.core.account

import com.yfuse.backend.BackendAccess
import kotlinx.serialization.Serializable

@Serializable
internal data class PendingAccountRefresh(
    val refreshToken: String,
    val requestId: String,
)

data class AccountSession(
    val user: AccountUser,
    val accessToken: String,
    val accessExpiresAtEpochMs: Long,
    val refreshExpiresAtEpochMs: Long,
)

sealed interface AccountState {
    data object SignedOut : AccountState

    data object Restoring : AccountState

    data class RestoreFailed(
        val message: String,
    ) : AccountState

    data class SignedIn(
        val session: AccountSession,
        val syncVersion: Long = 0,
        val cloudHasData: Boolean = false,
        val syncing: Boolean = false,
        val lastSyncedAtEpochMs: Long? = null,
        val message: String? = null,
    ) : AccountState
}

/** Together Watch is an account-bound service; every client surface uses this same gate. */
fun AccountState.canUseWatchTogether(): Boolean = BackendAccess.Default.enabled && this is AccountState.SignedIn
