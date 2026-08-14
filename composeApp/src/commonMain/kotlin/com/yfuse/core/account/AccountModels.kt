package com.yfuse.core.account

import kotlinx.serialization.Serializable

const val ACCOUNT_BASE_URL: String = "https://47.112.219.60"
const val INVITE_ISSUE_CAPABILITY: String = "invite:issue"

@Serializable
data class AccountUser(
    val id: String,
    val username: String,
    val nickname: String,
    val avatarId: Int,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    /** Server-authoritative permissions. An absent legacy field grants nothing. */
    val capabilities: List<String> = emptyList(),
)

fun AccountUser.canIssueInvites(): Boolean = INVITE_ISSUE_CAPABILITY in capabilities

@Serializable
internal data class RegisterRequest(
    val username: String,
    val password: String,
    val nickname: String? = null,
    val avatarId: Int? = null,
    val inviteCode: String? = null,
    val deviceName: String? = null,
)

@Serializable
internal data class LoginRequest(
    val username: String,
    val password: String,
    val deviceName: String? = null,
)

@Serializable
internal data class RefreshRequest(
    val refreshToken: String,
    val deviceName: String? = null,
)

@Serializable
internal data class DeleteAccountRequest(
    val password: String,
)

@Serializable
internal data class UpdateProfileRequest(
    val nickname: String? = null,
    val avatarId: Int? = null,
)

@Serializable
data class ChangePasswordRequest(
    val currentPassword: String,
    val newPassword: String,
    val expectedSyncVersion: Long,
    val keyVersion: Int,
    val wrappedVaultKey: String,
    val wrapSalt: String,
    val wrapNonce: String,
    val wrapVersion: Int,
    val wrapKdf: String,
    val wrapIterations: Int,
    val deviceName: String? = null,
)

@Serializable
data class AuthResponse(
    val user: AccountUser,
    val accessToken: String,
    val accessExpiresAtEpochMs: Long,
    val refreshToken: String,
    val refreshExpiresAtEpochMs: Long,
)

/** Opaque encrypted document. The server validates its shape but never decrypts it. */
@Serializable
data class EncryptedSyncPayload(
    val schemaVersion: Int = 1,
    val algorithm: String = "AES-256-GCM",
    val keyVersion: Int = 1,
    val nonce: String,
    val ciphertext: String,
    val wrappedVaultKey: String? = null,
    val wrapSalt: String? = null,
    val wrapNonce: String? = null,
    val wrapVersion: Int? = null,
    val wrapKdf: String? = null,
    val wrapIterations: Int? = null,
)

@Serializable
data class SyncResponse(
    val version: Long,
    val payload: EncryptedSyncPayload? = null,
    val updatedAtEpochMs: Long? = null,
)

@Serializable
data class AccountDeviceSession(
    val id: String,
    val deviceName: String,
    val createdAtEpochMs: Long,
    val lastSeenAtEpochMs: Long,
    val current: Boolean,
)

@Serializable
internal data class AccountSessionsResponse(
    val sessions: List<AccountDeviceSession>,
)

@Serializable
data class AccountExport(
    val schemaVersion: Int,
    val exportedAtEpochMs: Long,
    val user: AccountUser,
    val encryptedSync: SyncResponse,
)

@Serializable
internal data class PutSyncRequest(
    val baseVersion: Long,
    val payload: EncryptedSyncPayload,
)

@Serializable
internal data class ErrorBody(
    val code: String,
    val message: String,
    val currentVersion: Long? = null,
)

@Serializable
internal data class ErrorEnvelope(
    val error: ErrorBody,
)

@Serializable
data class IssuedInviteCode(
    val code: String,
    val expiresAtEpochMs: Long,
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
fun AccountState.canUseWatchTogether(): Boolean = this is AccountState.SignedIn
