package com.yfuse.watch.account

import kotlinx.serialization.Serializable

internal const val INVITE_ISSUE_CAPABILITY = "invite:issue"

/**
 * Registration in one of two shapes. Protocol 1 (builds up to 1.1.5) sends [password] itself.
 * Protocol 2 never lets the password leave the device: the client stretches it with
 * [kdfSalt]/[kdfIterations] into a master key, sends only the [authKey] derived from it, and
 * stores the vault key wrapped with a second, never-sent key from the same master ([vault]).
 */
@Serializable
internal data class RegisterRequest(
    val username: String,
    val password: String? = null,
    val nickname: String? = null,
    val avatarId: Int? = null,
    val inviteCode: String? = null,
    val deviceName: String? = null,
    val authKey: String? = null,
    val kdfSalt: String? = null,
    val kdfIterations: Int? = null,
    val vault: VaultEnvelope? = null,
)

/** Protocol 1 sends [password]; protocol 2 sends the derived [authKey] instead. */
@Serializable
internal data class LoginRequest(
    val username: String,
    val password: String? = null,
    val deviceName: String? = null,
    val authKey: String? = null,
)

@Serializable
internal data class PreloginRequest(
    val username: String,
)

/**
 * How a client must prove the password for [PreloginRequest.username]. Unknown usernames get
 * protocol 2 parameters derived from a server secret, so the answer does not reveal whether a
 * protocol-2 account exists; protocol 1 accounts answer without parameters until they upgrade.
 */
@Serializable
internal data class PreloginResponse(
    val authProtocol: Int,
    val kdf: String? = null,
    val kdfIterations: Int? = null,
    val kdfSalt: String? = null,
)

/**
 * The account's vault key wrapped by the client with a key the server never receives. Stored
 * apart from the sync document, so every device of an account opens the same vault even before
 * the first upload and after 清空云端.
 */
@Serializable
internal data class VaultEnvelope(
    val keyVersion: Int,
    val wrapVersion: Int,
    val nonce: String,
    val wrappedKey: String,
)

@Serializable
internal data class VaultResponse(
    val vault: VaultEnvelope? = null,
)

/**
 * Replaces the credentials and the vault key in one step: the protocol 1 → 2 upgrade proves
 * [currentPassword], a protocol 2 password change proves [currentAuthKey]. A new vault key is
 * mandatory, so a password that was ever seen by anyone else no longer opens future data, and
 * an existing sync document must come back re-encrypted under it in [sync].
 */
@Serializable
internal data class RekeyRequest(
    val currentPassword: String? = null,
    val currentAuthKey: String? = null,
    val authKey: String,
    val kdfSalt: String,
    val kdfIterations: Int,
    val vault: VaultEnvelope,
    val sync: PutSyncRequest? = null,
    val deviceName: String? = null,
)

@Serializable
internal data class RefreshRequest(
    val refreshToken: String,
    val deviceName: String? = null,
    val requestId: String? = null,
)

@Serializable
internal data class DeleteAccountRequest(
    val password: String? = null,
    val authKey: String? = null,
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
    val deviceName: String? = null,
)

@Serializable
internal data class UserResponse(
    val id: String,
    val username: String,
    val nickname: String,
    val avatarId: Int,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val capabilities: Set<String> = emptySet(),
)

@Serializable
internal data class IssuedInviteResponse(
    val code: String,
    val expiresAtEpochMs: Long,
)

@Serializable
internal data class AuthResponse(
    val user: UserResponse,
    val accessToken: String,
    val accessExpiresAtEpochMs: Long,
    val refreshToken: String,
    val refreshExpiresAtEpochMs: Long,
    /** Omitted (the default) for protocol 1 accounts, which older clients expect. */
    val authProtocol: Int = AUTH_PROTOCOL_PASSWORD,
    val vault: VaultEnvelope? = null,
)

@Serializable
internal data class AccountSessionResponse(
    val id: String,
    val deviceName: String,
    val createdAtEpochMs: Long,
    val lastSeenAtEpochMs: Long,
    val current: Boolean,
)

@Serializable
internal data class AccountSessionsResponse(
    val sessions: List<AccountSessionResponse>,
)

@Serializable
internal data class AccountExportResponse(
    val schemaVersion: Int,
    val exportedAtEpochMs: Long,
    val user: UserResponse,
    /** Still an opaque AES-GCM envelope; the account server never exports plaintext sync data. */
    val encryptedSync: SyncResponse,
    /** Protocol 2 accounts: the wrapped key [encryptedSync] is encrypted under. */
    val vault: VaultEnvelope? = null,
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
    /** Answering `knownVersion`: the document is still that version, so none is sent. */
    val unchanged: Boolean = false,
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

/**
 * For protocol 1 the digest is PBKDF2 of the password itself. For protocol 2 it is a PBKDF2
 * verifier of the client's auth key, and [kdfSalt]/[kdfIterations] are what the client needs
 * to derive that key again.
 */
internal data class StoredCredentials(
    val user: StoredUser,
    val passwordSalt: ByteArray,
    val passwordHash: ByteArray,
    val passwordIterations: Int,
    val authProtocol: Int = AUTH_PROTOCOL_PASSWORD,
    val kdfSalt: ByteArray? = null,
    val kdfIterations: Int? = null,
)

internal data class StoredVault(
    val userId: String,
    val keyVersion: Int,
    val wrapVersion: Int,
    val nonce: ByteArray,
    val wrappedKey: ByteArray,
    val updatedAtEpochMs: Long,
)

/** Protocol 2 credentials replacing whatever the account had. */
internal data class ReplacementCredentials(
    val verifier: PasswordDigest,
    val kdfSalt: ByteArray,
    val kdfIterations: Int,
)

internal sealed interface RekeyWriteResult {
    data class Changed(
        val vault: StoredVault,
    ) : RekeyWriteResult

    data class VersionConflict(
        val currentVersion: Long,
    ) : RekeyWriteResult

    data class KeyVersionConflict(
        val currentKeyVersion: Int,
    ) : RekeyWriteResult

    /** The account has a sync document that was not re-encrypted under the new key. */
    data object SyncRequired : RekeyWriteResult

    data object CredentialsChanged : RekeyWriteResult

    data object SessionInvalid : RekeyWriteResult
}

internal const val AUTH_PROTOCOL_PASSWORD = 1
internal const val AUTH_PROTOCOL_DERIVED_KEY = 2

internal data class NewSession(
    val id: String,
    val userId: String,
    val accessTokenHash: ByteArray,
    val refreshTokenHash: ByteArray,
    val accessExpiresAtEpochMs: Long,
    val refreshExpiresAtEpochMs: Long,
    val createdAtEpochMs: Long,
    val deviceName: String,
)

internal data class SessionReplacement(
    val id: String,
    val accessTokenHash: ByteArray,
    val refreshTokenHash: ByteArray,
    val accessExpiresAtEpochMs: Long,
    val refreshExpiresAtEpochMs: Long,
    val createdAtEpochMs: Long,
    val deviceName: String?,
)

internal data class AuthenticatedSession(
    val sessionId: String,
    val user: StoredUser,
    val accessExpiresAtEpochMs: Long,
)

internal data class RefreshedSession(
    val user: StoredUser,
    val accessExpiresAtEpochMs: Long,
    val refreshExpiresAtEpochMs: Long,
)

internal data class NewIssuedInvite(
    val digest: ByteArray,
    val issuerUserId: String,
    val createdAtEpochMs: Long,
    val expiresAtEpochMs: Long,
)

internal sealed interface InviteConsumptionResult {
    data object Consumed : InviteConsumptionResult

    data object Unavailable : InviteConsumptionResult
}

internal data class StoredSession(
    val id: String,
    val deviceName: String,
    val createdAtEpochMs: Long,
    val lastSeenAtEpochMs: Long,
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
    data class Saved(
        val record: StoredSyncRecord,
    ) : SyncWriteResult

    data class VersionConflict(
        val currentVersion: Long,
    ) : SyncWriteResult

    data object NonceReused : SyncWriteResult

    /** The vault was re-keyed; a document under any other key version would be unreadable. */
    data class KeyVersionConflict(
        val currentKeyVersion: Int,
    ) : SyncWriteResult

    data object SessionInvalid : SyncWriteResult
}

internal sealed interface SyncDeleteResult {
    data class Deleted(
        val state: StoredSyncState,
    ) : SyncDeleteResult

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

    data object InviteUnavailable : RegistrationWriteResult
}

internal enum class InvitationKind {
    Static,
    Issued,
}

internal sealed interface InviteIssueWriteResult {
    data object Created : InviteIssueWriteResult

    data object Forbidden : InviteIssueWriteResult

    data object SessionInvalid : InviteIssueWriteResult
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

    data class VersionConflict(
        val currentVersion: Long,
    ) : PasswordChangeWriteResult

    data class KeyVersionConflict(
        val currentVersion: Long,
    ) : PasswordChangeWriteResult

    data object CredentialsChanged : PasswordChangeWriteResult
}

internal sealed interface DeleteAccountWriteResult {
    data object Deleted : DeleteAccountWriteResult

    data object CredentialsChanged : DeleteAccountWriteResult

    data object SessionInvalid : DeleteAccountWriteResult
}
