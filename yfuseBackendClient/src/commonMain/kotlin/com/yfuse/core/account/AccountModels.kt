package com.yfuse.core.account

import com.yfuse.backend.BackendEndpoints
import kotlinx.serialization.Serializable

const val ACCOUNT_BASE_URL: String = BackendEndpoints.ORIGIN
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
data class RegisterRequest(
    val username: String,
    val password: String,
    val nickname: String? = null,
    val avatarId: Int? = null,
    val inviteCode: String? = null,
    val deviceName: String? = null,
)

@Serializable
data class LoginRequest(
    val username: String,
    val password: String,
    val deviceName: String? = null,
)

@Serializable
data class RefreshRequest(
    val refreshToken: String,
    val deviceName: String? = null,
    val requestId: String? = null,
)

/**
 * The refresh body every account backend has accepted. A backend deployed before the idempotent
 * refresh contract decodes requests strictly and rejects the `requestId` field as `invalid_json`,
 * so the client falls back to this shape rather than leaving the user signed out.
 */
@Serializable
data class LegacyRefreshRequest(
    val refreshToken: String,
)

@Serializable
data class DeleteAccountRequest(
    val password: String,
)

@Serializable
data class UpdateProfileRequest(
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
data class AccountSessionsResponse(
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
data class PutSyncRequest(
    val baseVersion: Long,
    val payload: EncryptedSyncPayload,
)

@Serializable
data class ErrorBody(
    val code: String,
    val message: String,
    val currentVersion: Long? = null,
)

@Serializable
data class ErrorEnvelope(
    val error: ErrorBody,
)

@Serializable
data class IssuedInviteCode(
    val code: String,
    val expiresAtEpochMs: Long,
)
