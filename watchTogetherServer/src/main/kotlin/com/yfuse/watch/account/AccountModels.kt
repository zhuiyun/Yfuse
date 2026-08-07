package com.yfuse.watch.account

import kotlinx.serialization.Serializable

@Serializable
internal data class RegisterRequest(
    val username: String,
    val password: String,
    val nickname: String? = null,
    val avatarId: Int? = null,
)

@Serializable
internal data class LoginRequest(
    val username: String,
    val password: String,
)

@Serializable
internal data class RefreshRequest(
    val refreshToken: String,
)

@Serializable
internal data class UpdateProfileRequest(
    val nickname: String? = null,
    val avatarId: Int? = null,
)

@Serializable
internal data class ChangePasswordRequest(
    val currentPassword: String,
    val newPassword: String,
    val expectedSyncVersion: Long,
    val keyVersion: Int,
    val wrapVersion: Int,
    val wrapKdf: String,
    val wrapIterations: Int,
    val wrappedVaultKey: String,
    val wrapSalt: String,
    val wrapNonce: String,
)

@Serializable
internal data class UserResponse(
    val id: String,
    val username: String,
    val nickname: String,
    val avatarId: Int,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

@Serializable
internal data class AuthResponse(
    val user: UserResponse,
    val accessToken: String,
    val accessExpiresAtEpochMs: Long,
    val refreshToken: String,
    val refreshExpiresAtEpochMs: Long,
)

/**
 * An opaque client-created envelope. The server validates its shape and size, but never
 * receives the key and has no code path that decrypts [ciphertext]. The 16-byte GCM tag is
 * appended to the ciphertext bytes.
 */
@Serializable
internal data class EncryptedSyncEnvelope(
    val schemaVersion: Int,
    val algorithm: String,
    val keyVersion: Int,
    val nonce: String,
    val ciphertext: String,
    val wrapVersion: Int? = null,
    val wrapKdf: String? = null,
    val wrapIterations: Int? = null,
    val wrappedVaultKey: String? = null,
    val wrapSalt: String? = null,
    val wrapNonce: String? = null,
)

@Serializable
internal data class PutSyncRequest(
    val baseVersion: Long,
    val payload: EncryptedSyncEnvelope,
)

@Serializable
internal data class SyncResponse(
    val version: Long,
    val payload: EncryptedSyncEnvelope? = null,
    val updatedAtEpochMs: Long? = null,
)

@Serializable
internal data class ApiErrorResponse(
    val error: ApiError,
)

@Serializable
internal data class ApiError(
    val code: String,
    val message: String,
    val currentVersion: Long? = null,
)

internal data class StoredUser(
    val id: String,
    val username: String,
    val normalizedUsername: String,
    val nickname: String,
    val avatarId: Int,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

internal data class StoredCredentials(
    val user: StoredUser,
    val passwordSalt: ByteArray,
    val passwordHash: ByteArray,
    val passwordIterations: Int,
)

internal data class NewSession(
    val id: String,
    val userId: String,
    val accessTokenHash: ByteArray,
    val refreshTokenHash: ByteArray,
    val accessExpiresAtEpochMs: Long,
    val refreshExpiresAtEpochMs: Long,
    val createdAtEpochMs: Long,
)

internal data class SessionReplacement(
    val id: String,
    val accessTokenHash: ByteArray,
    val refreshTokenHash: ByteArray,
    val accessExpiresAtEpochMs: Long,
    val refreshExpiresAtEpochMs: Long,
    val createdAtEpochMs: Long,
)

internal data class AuthenticatedSession(
    val sessionId: String,
    val user: StoredUser,
)

internal data class StoredSyncRecord(
    val userId: String,
    val version: Long,
    val schemaVersion: Int,
    val algorithm: String,
    val keyVersion: Int,
    val nonce: ByteArray,
    val ciphertext: ByteArray,
    val wrapVersion: Int?,
    val wrapKdf: String?,
    val wrapIterations: Int?,
    val wrappedVaultKey: ByteArray?,
    val wrapSalt: ByteArray?,
    val wrapNonce: ByteArray?,
    val updatedAtEpochMs: Long,
)

/**
 * Monotonic synchronization state. [record] is null for both the never-written state
 * (`version == 0`) and a deletion tombstone (`version > 0`).
 */
internal data class StoredSyncState(
    val version: Long,
    val record: StoredSyncRecord?,
    val updatedAtEpochMs: Long?,
)

internal sealed interface SyncWriteResult {
    data class Saved(val record: StoredSyncRecord) : SyncWriteResult
    data class VersionConflict(val currentVersion: Long) : SyncWriteResult
    data object NonceReused : SyncWriteResult
    data object SessionInvalid : SyncWriteResult
}

internal sealed interface SyncDeleteResult {
    data class Deleted(val state: StoredSyncState) : SyncDeleteResult
    data object SessionInvalid : SyncDeleteResult
}

internal enum class RegistrationAvailability {
    Available,
    UsernameUnavailable,
    Closed,
}

internal sealed interface RegistrationWriteResult {
    data object Created : RegistrationWriteResult
    data object UsernameUnavailable : RegistrationWriteResult
    data object Closed : RegistrationWriteResult
}

internal data class StoredKeyWrap(
    val keyVersion: Int,
    val wrapVersion: Int,
    val wrapKdf: String,
    val wrapIterations: Int,
    val wrappedVaultKey: ByteArray,
    val wrapSalt: ByteArray,
    val wrapNonce: ByteArray,
)

internal sealed interface PasswordChangeWriteResult {
    data object Changed : PasswordChangeWriteResult
    data class VersionConflict(val currentVersion: Long) : PasswordChangeWriteResult
    data class KeyVersionConflict(val currentVersion: Long) : PasswordChangeWriteResult
    data object CredentialsChanged : PasswordChangeWriteResult
}
