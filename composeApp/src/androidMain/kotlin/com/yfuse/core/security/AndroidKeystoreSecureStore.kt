package com.yfuse.core.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.russhwolf.settings.Settings
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

actual fun createSecureStore(
    settings: Settings,
    namespace: String,
): SecureStore = AndroidKeystoreSecureStore(settings, namespace)

/**
 * Android Keystore encrypted [SecureStore]. Ciphertexts live in the supplied [Settings] backend;
 * the non-exportable AES master key lives in Android Keystore.
 */
class AndroidKeystoreSecureStore(
    private val settings: Settings,
    namespace: String = "account",
) : SecureStore {
    private val namespace = validateNamespace(namespace)
    private val entryPrefix = "$SETTINGS_PREFIX${this.namespace}."
    private val keyAlias = "$KEY_ALIAS_PREFIX${sha256Hex(this.namespace)}"

    // All instances share a lock so clear() cannot rotate a namespace key between another
    // instance encrypting a value and persisting its ciphertext.
    private val lock = STORE_LOCK

    override fun get(key: String): ByteArray? =
        synchronized(lock) {
            val normalizedKey = validateEntryKey(key)
            val encoded = settings.getStringOrNull(storageKey(normalizedKey)) ?: return@synchronized null
            val envelope =
                try {
                    require(encoded.length <= MAX_STORED_BASE64_CHARS) {
                        "Secure-store value is too large"
                    }
                    SecureStoreEnvelopeCodec.decode(Base64.getDecoder().decode(encoded))
                } catch (error: IllegalArgumentException) {
                    throw SecureStoreCorruptedException("Secure-store value is malformed", error)
                }
            try {
                Cipher.getInstance(TRANSFORMATION).run {
                    init(
                        Cipher.DECRYPT_MODE,
                        getOrCreateMasterKey(),
                        GCMParameterSpec(GCM_TAG_SIZE_BITS, envelope.nonce),
                    )
                    updateAAD(entryAad(normalizedKey))
                    doFinal(envelope.ciphertext)
                }
            } catch (error: AEADBadTagException) {
                throw SecureStoreCorruptedException(cause = error)
            } catch (error: BadPaddingException) {
                throw SecureStoreCorruptedException(cause = error)
            } catch (error: GeneralSecurityException) {
                forgetMasterKey()
                throw SecureStoreException("Secure-store decryption failed", error)
            }
        }

    override fun put(
        key: String,
        value: ByteArray,
    ) = synchronized(lock) {
        val normalizedKey = validateEntryKey(key)
        require(value.size <= MAX_PLAINTEXT_SIZE_BYTES) {
            "Secure-store values are limited to $MAX_PLAINTEXT_SIZE_BYTES bytes"
        }
        val envelope =
            try {
                Cipher.getInstance(TRANSFORMATION).run {
                    init(Cipher.ENCRYPT_MODE, getOrCreateMasterKey())
                    updateAAD(entryAad(normalizedKey))
                    val encrypted = doFinal(value)
                    SecureStoreEnvelope(nonce = iv, ciphertext = encrypted)
                }
            } catch (error: GeneralSecurityException) {
                forgetMasterKey()
                throw SecureStoreException("Secure-store encryption failed", error)
            }
        val encoded = Base64.getEncoder().encodeToString(SecureStoreEnvelopeCodec.encode(envelope))
        settings.putString(storageKey(normalizedKey), encoded)
    }

    override fun remove(key: String): Boolean =
        synchronized(lock) {
            val storedKey = storageKey(validateEntryKey(key))
            val existed = storedKey in settings.keys
            settings.remove(storedKey)
            existed
        }

    override fun clear() =
        synchronized(lock) {
            settings.keys
                .filter { it.startsWith(entryPrefix) }
                .forEach(settings::remove)
            try {
                synchronized(KEYSTORE_LOCK) {
                    MASTER_KEYS.remove(keyAlias)
                    androidKeyStore().run {
                        if (containsAlias(keyAlias)) deleteEntry(keyAlias)
                    }
                }
            } catch (error: GeneralSecurityException) {
                throw SecureStoreException("Secure-store key could not be deleted", error)
            }
        }

    private fun storageKey(key: String): String = entryPrefix + sha256Hex(key)

    private fun entryAad(key: String): ByteArray =
        "$AAD_PREFIX\u0000$namespace\u0000$key".toByteArray(StandardCharsets.UTF_8)

    /**
     * The namespace's master key, looked up in the Keystore once per process.
     *
     * Loading the Keystore and fetching the key are binder calls into keystore2, and every get and
     * put used to repeat both: a cold start paid them once per saved token while restoring the
     * server registry. The handle stays valid until [clear] deletes the entry, which drops it from
     * the cache under the same lock; a failed crypto operation drops it too, so a key invalidated
     * behind our back is looked up again rather than failing every later call.
     */
    private fun getOrCreateMasterKey(): SecretKey =
        synchronized(KEYSTORE_LOCK) {
            MASTER_KEYS[keyAlias]?.let { return@synchronized it }
            val keyStore = androidKeyStore()
            val key =
                (keyStore.getKey(keyAlias, null) as? SecretKey) ?: run {
                    val generator =
                        KeyGenerator.getInstance(
                            KeyProperties.KEY_ALGORITHM_AES,
                            ANDROID_KEYSTORE,
                        )
                    generator.init(
                        KeyGenParameterSpec
                            .Builder(
                                keyAlias,
                                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .setKeySize(VaultCrypto.AES_KEY_SIZE_BYTES * Byte.SIZE_BITS)
                            .setRandomizedEncryptionRequired(true)
                            .build(),
                    )
                    generator.generateKey()
                }
            MASTER_KEYS[keyAlias] = key
            key
        }

    private fun forgetMasterKey() {
        synchronized(KEYSTORE_LOCK) { MASTER_KEYS.remove(keyAlias) }
    }

    // Called under KEYSTORE_LOCK. Loading is a service round trip too, and a loaded instance reads
    // the live entries of every alias, so one serves the whole process.
    private fun androidKeyStore(): KeyStore {
        loadedKeyStore?.let { return it }
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)
        loadedKeyStore = keyStore
        return keyStore
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_SIZE_BITS = 128
        private const val MAX_PLAINTEXT_SIZE_BYTES = 64 * 1024
        private const val MAX_STORED_BASE64_CHARS = 88 * 1024
        private const val SETTINGS_PREFIX = "secure.store.v1."
        private const val KEY_ALIAS_PREFIX = "com.yfuse.secure-store.v1."
        private const val AAD_PREFIX = "yfuse-secure-store-v1"
        private val VALID_NAMESPACE = Regex("[A-Za-z0-9._-]{1,64}")
        private val STORE_LOCK = Any()
        private val KEYSTORE_LOCK = Any()

        // Per alias rather than per instance: two stores of one namespace share a key, and clear()
        // on either must not leave the other holding a handle to a deleted entry.
        // Both guarded by KEYSTORE_LOCK.
        private val MASTER_KEYS = HashMap<String, SecretKey>()
        private var loadedKeyStore: KeyStore? = null

        private fun validateNamespace(value: String): String =
            value.also {
                require(VALID_NAMESPACE.matches(it)) {
                    "Secure-store namespace must match ${VALID_NAMESPACE.pattern}"
                }
            }

        private fun validateEntryKey(value: String): String =
            value.also {
                val size = it.toByteArray(StandardCharsets.UTF_8).size
                require(it.isNotBlank() && size <= 256) {
                    "Secure-store key must contain 1..256 UTF-8 bytes"
                }
            }

        private fun sha256Hex(value: String): String =
            MessageDigest
                .getInstance("SHA-256")
                .digest(value.toByteArray(StandardCharsets.UTF_8))
                .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}

internal data class SecureStoreEnvelope(
    val nonce: ByteArray,
    val ciphertext: ByteArray,
)

internal object SecureStoreEnvelopeCodec {
    private val MAGIC = byteArrayOf(0x59, 0x46, 0x53, 0x53) // YFSS
    private const val VERSION: Byte = 1
    private const val NONCE_SIZE_BYTES = 12
    private const val TAG_SIZE_BYTES = 16
    private const val MAX_CIPHERTEXT_SIZE_BYTES = (64 * 1024) + TAG_SIZE_BYTES
    private const val HEADER_SIZE_BYTES = 4 + 1 + 1 + Int.SIZE_BYTES

    fun encode(value: SecureStoreEnvelope): ByteArray {
        require(value.nonce.size == NONCE_SIZE_BYTES) { "Invalid secure-store nonce" }
        require(value.ciphertext.size in TAG_SIZE_BYTES..MAX_CIPHERTEXT_SIZE_BYTES) {
            "Invalid secure-store ciphertext size"
        }
        return ByteBuffer
            .allocate(HEADER_SIZE_BYTES + value.nonce.size + value.ciphertext.size)
            .put(MAGIC)
            .put(VERSION)
            .put(value.nonce.size.toByte())
            .putInt(value.ciphertext.size)
            .put(value.nonce)
            .put(value.ciphertext)
            .array()
    }

    fun decode(encoded: ByteArray): SecureStoreEnvelope {
        require(encoded.size >= HEADER_SIZE_BYTES + NONCE_SIZE_BYTES + TAG_SIZE_BYTES) {
            "Secure-store envelope is truncated"
        }
        val buffer = ByteBuffer.wrap(encoded)
        val magic = ByteArray(MAGIC.size).also(buffer::get)
        require(magic.contentEquals(MAGIC)) { "Invalid secure-store envelope magic" }
        require(buffer.get() == VERSION) { "Unsupported secure-store envelope version" }
        val nonceSize = buffer.get().toInt() and 0xff
        require(nonceSize == NONCE_SIZE_BYTES) { "Invalid secure-store nonce size" }
        val ciphertextSize = buffer.int
        require(ciphertextSize in TAG_SIZE_BYTES..MAX_CIPHERTEXT_SIZE_BYTES) {
            "Invalid secure-store ciphertext size"
        }
        require(buffer.remaining() == nonceSize + ciphertextSize) {
            "Invalid secure-store envelope length"
        }
        val nonce = ByteArray(nonceSize).also(buffer::get)
        val ciphertext = ByteArray(ciphertextSize).also(buffer::get)
        return SecureStoreEnvelope(nonce, ciphertext)
    }
}
