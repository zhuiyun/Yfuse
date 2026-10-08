package com.yfuse.core.account

import com.yfuse.core.security.SecureStore
import com.yfuse.core.security.VaultCrypto
import com.yfuse.core.security.base64UrlToBytes
import com.yfuse.core.security.toBase64Url

internal const val ACCOUNT_PROTOCOL_PASSWORD = 1
internal const val ACCOUNT_PROTOCOL_DERIVED_KEY = 2
internal const val ACCOUNT_KDF = "PBKDF2-HMAC-SHA256"
internal const val ACCOUNT_KDF_SALT_BYTES = 16
internal const val ACCOUNT_VAULT_WRAP_VERSION = 2

/**
 * The fewest PBKDF2 rounds this app accepts from the account service. Every protocol 2 account
 * this app registers uses exactly this many; a service asking for fewer would only be making the
 * key it receives cheaper to brute-force back into the password.
 */
internal const val ACCOUNT_KDF_MIN_ITERATIONS = VaultCrypto.DEFAULT_PBKDF2_ITERATIONS

/** How the account service wants an account's password proved, from its prelogin answer. */
internal sealed interface LoginProof {
    /** The service predates protocol 2: the password itself, with nothing to upgrade to. */
    data object Legacy : LoginProof

    /** A protocol 1 account on a service that knows protocol 2: the password, then an upgrade. */
    data object Password : LoginProof

    /** Protocol 2: the password is stretched on this device and only a derived key is sent. */
    class DerivedKey(
        val salt: ByteArray,
        val iterations: Int,
    ) : LoginProof
}

internal fun PreloginResponse?.toLoginProof(): LoginProof {
    if (this == null) return LoginProof.Legacy
    return when (authProtocol) {
        ACCOUNT_PROTOCOL_PASSWORD -> LoginProof.Password
        ACCOUNT_PROTOCOL_DERIVED_KEY -> {
            require(kdf == ACCOUNT_KDF) { "账号服务要求的密钥算法不受支持，请更新 Yfuse" }
            val rounds = requireNotNull(kdfIterations) { "账号服务没有返回密钥参数" }
            require(rounds in ACCOUNT_KDF_MIN_ITERATIONS..VaultCrypto.MAX_PBKDF2_ITERATIONS) {
                "账号服务返回的密钥参数不安全，已停止登录"
            }
            val salt = requireNotNull(kdfSalt) { "账号服务没有返回密钥参数" }.base64UrlToBytes()
            require(salt.size == ACCOUNT_KDF_SALT_BYTES) { "账号服务返回的密钥参数无效" }
            LoginProof.DerivedKey(salt, rounds)
        }
        else -> error("账号服务要求的登录方式不受支持，请更新 Yfuse")
    }
}

/**
 * Remembers, across sign-outs, which accounts this device has seen use protocol 2. Protocol 2
 * exists so the account service never receives the password; a service that later answers that
 * such an account takes the password again (or that it no longer knows protocol 2) is refused
 * instead of being sent it. Only a salted hash of each name is kept.
 */
internal class AccountProtocolMemory(
    private val secureStore: SecureStore,
    private val crypto: VaultCrypto,
) {
    fun requirePasswordMayBeSent(username: String) {
        check(tag(username) !in read()) {
            "此账号已在本机升级为更安全的登录方式，账号服务却要求直接发送密码，为保护密码已停止登录。" +
                "若服务器刚从备份恢复，请联系服务器管理员。"
        }
    }

    fun rememberDerivedKey(username: String) {
        val entries = read()
        val tag = tag(username)
        if (tag !in entries) write(entries + tag)
    }

    fun forget(username: String) {
        val entries = read()
        val tag = tag(username)
        if (tag in entries) write(entries - tag)
    }

    /** Runs [clear] (a full secure-store wipe) and puts this memory back afterwards. */
    fun preservedAcross(clear: () -> Unit) {
        val saved = runCatching { secureStore.get(KEY) }.getOrNull()
        clear()
        if (saved != null) runCatching { secureStore.put(KEY, saved) }
    }

    private fun tag(username: String): String {
        val digest = crypto.sha256("yfuse-protocol2-account\u0000${username.trim().lowercase()}".encodeToByteArray())
        return try {
            digest.toBase64Url()
        } finally {
            digest.fill(0)
        }
    }

    private fun read(): Set<String> =
        runCatching { secureStore.get(KEY)?.decodeToString() }
            .getOrNull()
            ?.split('\n')
            ?.filterTo(linkedSetOf(), String::isNotEmpty)
            .orEmpty()

    private fun write(entries: Set<String>) {
        // A device signs into a handful of accounts; the cap only bounds a pathological store.
        secureStore.put(
            KEY,
            entries
                .toList()
                .takeLast(MAX_ENTRIES)
                .joinToString("\n")
                .encodeToByteArray(),
        )
    }

    private companion object {
        const val KEY = "account_protocol2_names"
        const val MAX_ENTRIES = 64
    }
}
