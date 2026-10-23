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

/** Account protocol 2: asks how the password must be proved. */
@Serializable
data class PreloginRequest(
    val username: String,
)

/**
 * `authProtocol` 1 means the account still logs in with the password; 2 comes with the PBKDF2
 * parameters the device stretches it with. Unknown names are answered like protocol 2 accounts.
 */
@Serializable
data class PreloginResponse(
    val authProtocol: Int,
    val kdf: String? = null,
    val kdfIterations: Int? = null,
    val kdfSalt: String? = null,
)

/** The account's vault key, wrapped on the device with a key the account service never sees. */
@Serializable
data class VaultEnvelope(
    val keyVersion: Int,
    val wrapVersion: Int,
    val nonce: String,
    val wrappedKey: String,
)

@Serializable
data class RegisterRequestV2(
    val username: String,
    val authKey: String,
    val kdfSalt: String,
    val kdfIterations: Int,
    val vault: VaultEnvelope,
    val nickname: String? = null,
    val avatarId: Int? = null,
    val inviteCode: String? = null,
    val deviceName: String? = null,
)

@Serializable
data class LoginRequestV2(
    val username: String,
    val authKey: String,
    val deviceName: String? = null,
)

/**
 * Replaces the credentials and the vault key at once: the protocol 1 → 2 upgrade proves
 * [currentPassword], a protocol 2 password change proves [currentAuthKey]. An existing sync
 * document travels along re-encrypted under the new key.
 */
@Serializable
data class RekeyRequest(
    val currentPassword: String? = null,
    val currentAuthKey: String? = null,
    val authKey: String,
    val kdfSalt: String,
    val kdfIterations: Int,
    val vault: VaultEnvelope,
    val sync: PutSyncRequest? = null,
    val deviceName: String? = null,
    val playbackMigrationId: String? = null,
    val playbackCursor: Long? = null,
)

@Serializable
data class PlaybackRekeyItem(
    val sourceEntityKey: String,
    val sourceCursor: Long,
    val entity: com.yfuse.core.sync.playback.EncryptedPlaybackEntity,
)

@Serializable
data class PlaybackRekeyBatch(
    val migrationId: String,
    val keyVersion: Int,
    val items: List<PlaybackRekeyItem> = emptyList(),
)

@Serializable
data class PlaybackRekeyReceipt(
    val migrationId: String,
)

@Serializable
data class DeleteAccountRequestV2(
    val authKey: String,
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
    /** 2 once the account logs in with a device-derived key; older services omit it. */
    val authProtocol: Int = 1,
    /** Protocol 2 only: the wrapped vault key every device of the account opens. */
    val vault: VaultEnvelope? = null,
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
    /** The document is still the `knownVersion` asked about, so none was sent. */
    val unchanged: Boolean = false,
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
    val vault: VaultEnvelope? = null,
    /** Password derivation parameters needed to open a protocol 2 export without the service. */
    val authProtocol: Int = 1,
    val kdf: String? = null,
    val kdfIterations: Int? = null,
    val kdfSalt: String? = null,
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
