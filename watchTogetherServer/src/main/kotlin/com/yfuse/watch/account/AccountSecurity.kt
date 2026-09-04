package com.yfuse.watch.account

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

internal data class PasswordDigest(
    val salt: ByteArray,
    val hash: ByteArray,
    val iterations: Int,
)

internal interface PasswordHasher {
    fun hash(password: String): PasswordDigest

    fun verify(
        password: String,
        expected: PasswordDigest,
    ): Boolean
}

/**
 * Uses the JCA implementation of PBKDF2-HMAC-SHA256 with OWASP's 600,000-iteration
 * parameter, a per-password 128-bit salt, and a 256-bit result. Argon2id is preferable in
 * a deployment that already has a vetted native/runtime binding; this standalone JVM
 * service deliberately uses the audited JCA primitive to avoid adding another native crypto
 * binding alongside the required SQLite driver. The iteration count is stored with every
 * user so it can be raised later without invalidating accounts.
 */
internal class Pbkdf2PasswordHasher(
    private val iterations: Int = PRODUCTION_ITERATIONS,
    private val random: SecureRandom = SecureRandom(),
) : PasswordHasher {
    init {
        require(iterations > 0) { "iterations must be positive" }
    }

    override fun hash(password: String): PasswordDigest {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        return PasswordDigest(
            salt = salt,
            hash = derive(password, salt, iterations),
            iterations = iterations,
        )
    }

    override fun verify(
        password: String,
        expected: PasswordDigest,
    ): Boolean {
        val actual = derive(password, expected.salt, expected.iterations)
        return try {
            MessageDigest.isEqual(expected.hash, actual)
        } finally {
            actual.fill(0)
        }
    }

    private fun derive(
        password: String,
        salt: ByteArray,
        rounds: Int,
    ): ByteArray {
        val chars = password.toCharArray()
        val spec = PBEKeySpec(chars, salt, rounds, HASH_BITS)
        return try {
            SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
            chars.fill('\u0000')
        }
    }

    companion object {
        internal const val PRODUCTION_ITERATIONS = 600_000
        private const val ALGORITHM = "PBKDF2WithHmacSHA256"
        private const val SALT_BYTES = 16
        private const val HASH_BITS = 256
    }
}

internal data class IssuedToken(
    val plaintext: String,
    val hash: ByteArray,
)

internal class SessionTokenFactory(
    private val random: SecureRandom = SecureRandom(),
) {
    fun issue(): IssuedToken {
        val bytes = ByteArray(TOKEN_BYTES).also(random::nextBytes)
        return try {
            val plaintext = encoder.encodeToString(bytes)
            IssuedToken(plaintext, digest(plaintext))
        } finally {
            bytes.fill(0)
        }
    }

    fun digest(raw: String): ByteArray =
        MessageDigest
            .getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.US_ASCII))

    /** Domain-separated successors let a persisted refresh request recover its exact result. */
    fun refreshSuccessor(
        rawToken: String,
        requestId: String,
        purpose: String,
    ): IssuedToken {
        val key = rawToken.toByteArray(Charsets.US_ASCII)
        val bytes =
            try {
                Mac.getInstance("HmacSHA256").run {
                    init(SecretKeySpec(key, "HmacSHA256"))
                    doFinal("yfuse-refresh-v1:$purpose:$requestId".toByteArray(Charsets.US_ASCII))
                }
            } finally {
                key.fill(0)
            }
        return try {
            val plaintext = encoder.encodeToString(bytes)
            IssuedToken(plaintext, digest(plaintext))
        } finally {
            bytes.fill(0)
        }
    }

    fun isWellFormed(raw: String): Boolean {
        if (raw.length != ENCODED_TOKEN_LENGTH || !TOKEN_PATTERN.matches(raw)) return false
        return runCatching { decoder.decode(raw).size == TOKEN_BYTES }.getOrDefault(false)
    }

    companion object {
        private const val TOKEN_BYTES = 32
        private const val ENCODED_TOKEN_LENGTH = 43
        private val TOKEN_PATTERN = Regex("[A-Za-z0-9_-]+")
        private val encoder = Base64.getUrlEncoder().withoutPadding()
        private val decoder = Base64.getUrlDecoder()
    }
}
