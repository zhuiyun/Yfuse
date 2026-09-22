package com.yfuse.core.playback

import com.russhwolf.settings.Settings
import com.yfuse.core.security.SecureStore
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
enum class PlaybackOfflineLicenseState {
    Usable,
    RenewalRequired,
    Expired,
    DurationUnknown,
}

@Serializable
data class PlaybackOfflineLicense(
    val id: String,
    val scheme: PlaybackDrmScheme,
    val acquiredAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val licenseExpiresAtEpochMs: Long? = null,
    val playbackExpiresAtEpochMs: Long? = null,
    val state: PlaybackOfflineLicenseState = PlaybackOfflineLicenseState.DurationUnknown,
)

/** All request credentials are transient and are never written to the license catalog. */
data class PlaybackOfflineLicenseRequest(
    val mediaUri: String,
    val configuration: PlaybackDrmConfiguration,
    val drmInitializationData: ByteArray? = null,
    val drmMimeType: String = "video/mp4",
    val mediaRequestHeaders: Map<String, String> = emptyMap(),
)

interface PlaybackOfflineLicenseManager {
    fun licenses(): List<PlaybackOfflineLicense>

    suspend fun acquire(request: PlaybackOfflineLicenseRequest): PlaybackOfflineLicense

    suspend fun status(licenseId: String): PlaybackOfflineLicense

    suspend fun renew(
        licenseId: String,
        configuration: PlaybackDrmConfiguration,
    ): PlaybackOfflineLicense

    suspend fun renewIfNeeded(
        licenseId: String,
        configuration: PlaybackDrmConfiguration,
        thresholdSeconds: Long = DEFAULT_OFFLINE_LICENSE_RENEWAL_THRESHOLD_SECONDS,
    ): PlaybackOfflineLicense

    suspend fun release(
        licenseId: String,
        configuration: PlaybackDrmConfiguration,
    )

    /** Deletes only the local encrypted key and metadata after an unrecoverable server release. */
    suspend fun forget(licenseId: String)

    /** Materializes the secret only at the Android MediaDrm playback boundary. */
    fun configurationWithLicense(
        licenseId: String,
        configuration: PlaybackDrmConfiguration,
    ): PlaybackDrmConfiguration
}

expect fun createPlaybackOfflineLicenseManager(settings: Settings): PlaybackOfflineLicenseManager

class PlaybackOfflineLicenseException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

const val DEFAULT_OFFLINE_LICENSE_RENEWAL_THRESHOLD_SECONDS = 7L * 24L * 60L * 60L

/** Metadata is ordinary settings data; key-set bytes are only ever delegated to [SecureStore]. */
internal class PlaybackOfflineLicenseCatalog(
    private val settings: Settings,
    private val secureStore: SecureStore,
    private val nowEpochMs: () -> Long = { System.currentTimeMillis() },
) {
    private val lock = Any()
    private val serializer = ListSerializer(PlaybackOfflineLicense.serializer())
    private val json = Json { ignoreUnknownKeys = true }

    fun list(): List<PlaybackOfflineLicense> = synchronized(lock) { read().map(::refreshState) }

    fun get(id: String): PlaybackOfflineLicense =
        synchronized(lock) {
            read().firstOrNull { it.id == id }?.let(::refreshState)
                ?: throw PlaybackOfflineLicenseException("Offline DRM license does not exist")
        }

    fun keySetId(id: String): ByteArray =
        secureStore.get(secretKey(id))
            ?: throw PlaybackOfflineLicenseException("Offline DRM key is missing")

    fun put(
        license: PlaybackOfflineLicense,
        keySetId: ByteArray,
    ): PlaybackOfflineLicense =
        synchronized(lock) {
            validateLicenseId(license.id)
            require(keySetId.isNotEmpty()) { "Offline DRM key cannot be empty" }
            val previousKey = secureStore.get(secretKey(license.id))
            val stored = refreshState(license)
            try {
                secureStore.put(secretKey(license.id), keySetId)
                val updated = read().filterNot { it.id == license.id } + stored
                try {
                    write(updated)
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    if (previousKey == null) {
                        secureStore.remove(secretKey(license.id))
                    } else {
                        secureStore.put(secretKey(license.id), previousKey)
                    }
                    throw error
                }
                stored
            } finally {
                previousKey?.fill(0)
            }
        }

    fun update(license: PlaybackOfflineLicense) =
        synchronized(lock) {
            get(license.id)
            write(read().map { if (it.id == license.id) refreshState(license) else it })
        }

    fun remove(id: String) {
        synchronized(lock) {
            validateLicenseId(id)
            val previous = read()
            write(previous.filterNot { it.id == id })
            try {
                secureStore.remove(secretKey(id))
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                runCatching { write(previous) }.exceptionOrNull()?.let(error::addSuppressed)
                throw error
            }
        }
    }

    private fun read(): List<PlaybackOfflineLicense> {
        val raw = settings.getStringOrNull(INDEX_KEY) ?: return emptyList()
        return runCatching { json.decodeFromString(serializer, raw) }
            .getOrElse { throw PlaybackOfflineLicenseException("Offline DRM catalog is corrupted", it) }
    }

    private fun write(values: List<PlaybackOfflineLicense>) {
        if (values.isEmpty()) {
            settings.remove(INDEX_KEY)
        } else {
            settings.putString(INDEX_KEY, json.encodeToString(serializer, values.sortedBy { it.id }))
        }
    }

    private fun refreshState(value: PlaybackOfflineLicense): PlaybackOfflineLicense {
        val now = nowEpochMs()
        val earliestExpiry =
            listOfNotNull(value.licenseExpiresAtEpochMs, value.playbackExpiresAtEpochMs).minOrNull()
        val state =
            when {
                earliestExpiry == null -> PlaybackOfflineLicenseState.DurationUnknown
                earliestExpiry <= now -> PlaybackOfflineLicenseState.Expired
                earliestExpiry - now <= DEFAULT_OFFLINE_LICENSE_RENEWAL_THRESHOLD_SECONDS * 1_000L ->
                    PlaybackOfflineLicenseState.RenewalRequired
                else -> PlaybackOfflineLicenseState.Usable
            }
        return value.copy(state = state)
    }

    private fun secretKey(id: String): String = "license.$id"

    private fun validateLicenseId(id: String) {
        require(LICENSE_ID.matches(id)) { "Invalid offline DRM license id" }
    }

    private companion object {
        const val INDEX_KEY = "playback.offline-drm.v1.index"
        val LICENSE_ID = Regex("[A-Za-z0-9-]{1,64}")
    }
}
