package com.yfuse.core.security

import com.russhwolf.settings.Settings

/**
 * Small, synchronous key/value store for secrets.
 *
 * Values are copied at the API boundary so callers cannot mutate retained plaintext. Implementations
 * must encrypt values before writing them to their persistence backend.
 */
interface SecureStore {
    fun get(key: String): ByteArray?

    fun put(key: String, value: ByteArray)

    fun remove(key: String): Boolean

    fun clear()
}

/**
 * Creates a store backed by [settings], with encryption keys held by the platform secure keystore.
 *
 * A namespace owns both its settings entries and its platform key. Use a stable namespace for data
 * that must remain readable across application launches.
 */
expect fun createSecureStore(
    settings: Settings,
    namespace: String = "account",
): SecureStore

open class SecureStoreException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/** Stored bytes are malformed, were tampered with, or can no longer be decrypted. */
class SecureStoreCorruptedException(
    message: String = "Secure-store value could not be authenticated",
    cause: Throwable? = null,
) : SecureStoreException(message, cause)
