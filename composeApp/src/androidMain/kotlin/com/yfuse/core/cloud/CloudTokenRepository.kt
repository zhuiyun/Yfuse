package com.yfuse.core.cloud

import android.content.Context
import com.russhwolf.settings.SharedPreferencesSettings
import com.yfuse.core.security.SecureStore
import com.yfuse.core.security.SecureStoreCorruptedException
import com.yfuse.core.security.createSecureStore
import com.yfuse.core.util.androidAppContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.security.MessageDigest

/** Rotation survives process death; changed credentials cannot reuse another account's token. */
internal interface CloudTokenRepository {
    fun read(key: String): CloudDriveCredentials?

    fun write(
        key: String,
        credentials: CloudDriveCredentials,
    )
}

internal class EncryptedCloudTokenRepository(
    private val providedStore: SecureStore? = null,
) : CloudTokenRepository {
    private val store: SecureStore by lazy {
        providedStore ?: run {
            val context = checkNotNull(androidAppContext)
            createSecureStore(
                SharedPreferencesSettings(context.getSharedPreferences("cloud-tokens", Context.MODE_PRIVATE)),
                "cloud-tokens",
            )
        }
    }

    override fun read(key: String): CloudDriveCredentials? {
        val bytes =
            synchronized(lock) {
                if (key.substringBefore(':') in forgotten) {
                    null
                } else {
                    try {
                        store.get(key.substringBefore(':'))
                    } catch (_: SecureStoreCorruptedException) {
                        store.remove(key.substringBefore(':'))
                        null
                    }
                }
            }
        val entry =
            bytes?.decodeToString()?.let { Json.parseToJsonElement(it).jsonObject }
                ?: return null
        if (entry["identity"]?.jsonPrimitive?.content != key) return null
        return entry["credentials"]?.jsonPrimitive?.content?.let(CloudDriveCredentials::decode)
    }

    override fun write(
        key: String,
        credentials: CloudDriveCredentials,
    ) {
        val entry =
            buildJsonObject {
                put("identity", key)
                put("credentials", credentials.encode())
            }
        synchronized(lock) {
            if (key.substringBefore(':') !in forgotten) {
                store.put(key.substringBefore(':'), entry.toString().encodeToByteArray())
            }
        }
    }

    fun forget(sourceId: String) {
        synchronized(lock) {
            val key = cloudDigest(sourceId)
            forgotten.add(key)
            store.remove(key)
        }
    }

    private companion object {
        val lock = Any()

        // A late in-flight token rotation must not resurrect credentials after source deletion.
        val forgotten = mutableSetOf<String>()
    }
}

actual fun forgetCloudDriveAuthorization(sourceId: String) {
    EncryptedCloudTokenRepository().forget(sourceId)
}

internal fun cloudDigest(text: String): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(text.toByteArray())
        .joinToString("") { "%02x".format(it) }
