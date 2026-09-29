package com.yfuse.core.filesource

import com.russhwolf.settings.Settings
import com.yfuse.core.logging.AppLog
import com.yfuse.core.security.SecureStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.random.Random

@Serializable
private data class PersistedFileSources(
    @SerialName("v") val version: Int,
    @SerialName("s") val sources: List<PersistedFileSource> = emptyList(),
)

/** Non-secret metadata only; the password is in the secure store under [passwordKey]. */
@Serializable
private data class PersistedFileSource(
    @SerialName("i") val id: String,
    @SerialName("k") val kind: FileSourceKind,
    @SerialName("n") val name: String,
    @SerialName("o") val origin: String,
    @SerialName("r") val rootSegments: List<String> = emptyList(),
    @SerialName("u") val username: String = "",
)

/**
 * The 文件来源 the user has added, and the one place their passwords are read and written.
 *
 * Mirrors [com.yfuse.core.data.ServerRegistry]'s split: ordinary settings hold only what a card
 * shows, and each password is encrypted by [secureStore] under a key of its own. Unlike a server
 * session, nothing here needs a password to draw the list, so passwords are decrypted only when a
 * connection is about to be made — [credentials] — and never at construction, where a Keystore
 * round trip per source would sit on the app's start.
 *
 * Writes go through [ioDispatcher]: the secure store commits synchronously, which does not belong
 * on the thread that tapped 保存.
 */
class FileSourceRegistry(
    private val settings: Settings,
    private val secureStore: SecureStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val newId: () -> String = ::randomFileSourceId,
) {
    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
    private val writes = Mutex()
    private val _sources = MutableStateFlow(load())
    val sources: StateFlow<List<FileSource>> = _sources.asStateFlow()

    fun source(id: String): FileSource? = _sources.value.firstOrNull { it.id == id }

    /** A fresh id for a source about to be added; stable from then on. */
    fun newSourceId(): String = newId()

    /**
     * Adds [source], or replaces the one with its id in place.
     *
     * [password] null keeps whatever is stored — an edit that leaves 密码 blank — and an empty
     * one removes it, as does a source with no user name, which connects as a guest.
     */
    suspend fun save(
        source: FileSource,
        password: String?,
    ) = withContext(ioDispatcher) {
        writes.withLock {
            val current = _sources.value
            val replacing = current.any { it.id == source.id }
            require(replacing || current.size < MAX_FILE_SOURCES) { "文件来源数量已达上限" }
            require(source.id.matches(VALID_ID)) { "文件来源标识无效" }
            require(password == null || password.length <= MAX_PASSWORD_CHARS) { "密码过长" }
            val normalized = source.copy(name = source.name.take(MAX_SOURCE_NAME_CHARS))
            val key = passwordKey(source.id)
            when {
                normalized.username.isBlank() || password?.isEmpty() == true -> secureStore.remove(key)
                password != null -> {
                    val bytes = password.encodeToByteArray()
                    try {
                        secureStore.put(key, bytes)
                    } finally {
                        bytes.fill(0)
                    }
                }
            }
            val updated =
                if (replacing) {
                    current.map { if (it.id == source.id) normalized else it }
                } else {
                    current + normalized
                }
            persist(updated)
            _sources.value = updated
            AppLog.info(
                category = LOG_CATEGORY,
                event = if (replacing) "source_updated" else "source_added",
                message = "File source list changed",
                attributes =
                    mapOf(
                        "sourceId" to source.id,
                        "kind" to source.kind.name,
                        "sourceCount" to updated.size.toString(),
                    ),
            )
        }
    }

    suspend fun remove(id: String) =
        withContext(ioDispatcher) {
            writes.withLock {
                val current = _sources.value
                if (current.none { it.id == id }) return@withLock
                val updated = current.filterNot { it.id == id }
                persist(updated)
                _sources.value = updated
                runCatching { secureStore.remove(passwordKey(id)) }.onFailure { error ->
                    AppLog.warning(
                        category = LOG_CATEGORY,
                        event = "password_remove_failed",
                        message = "A removed file source's password could not be deleted",
                        attributes = mapOf("sourceId" to id, "exception" to error::class.simpleName.orEmpty()),
                    )
                }
                AppLog.info(
                    category = LOG_CATEGORY,
                    event = "source_removed",
                    message = "File source removed",
                    attributes = mapOf("sourceId" to id, "sourceCount" to updated.size.toString()),
                )
            }
        }

    /**
     * The login for [id], decrypted now; null when the source is gone.
     *
     * A password the secure store can no longer authenticate — a Keystore reset, a restore onto
     * another device — reads as empty rather than failing, so the share answers 401 and the UI
     * asks for it again instead of the source becoming impossible to open or to edit.
     */
    suspend fun credentials(id: String): FileSourceCredentials? =
        withContext(ioDispatcher) {
            val source = source(id) ?: return@withContext null
            if (source.username.isBlank()) return@withContext FileSourceCredentials("", "")
            val bytes =
                try {
                    secureStore.get(passwordKey(id))
                } catch (error: Exception) {
                    AppLog.warning(
                        category = LOG_CATEGORY,
                        event = "password_unreadable",
                        message = "A file source password could not be decrypted",
                        attributes = mapOf("sourceId" to id, "exception" to error::class.simpleName.orEmpty()),
                    )
                    null
                }
            val password =
                try {
                    bytes?.decodeToString().orEmpty()
                } finally {
                    bytes?.fill(0)
                }
            FileSourceCredentials(source.username, password)
        }

    private fun persist(sources: List<FileSource>) {
        val persisted =
            PersistedFileSources(
                version = PERSISTED_VERSION,
                sources =
                    sources.map {
                        PersistedFileSource(
                            id = it.id,
                            kind = it.kind,
                            name = it.name,
                            origin = it.origin,
                            rootSegments = it.rootSegments,
                            username = it.username,
                        )
                    },
            )
        settings.putString(SETTINGS_KEY, json.encodeToString(PersistedFileSources.serializer(), persisted))
    }

    private fun load(): List<FileSource> {
        val raw = settings.getStringOrNull(SETTINGS_KEY) ?: return emptyList()
        val persisted =
            runCatching { json.decodeFromString(PersistedFileSources.serializer(), raw) }
                .getOrNull()
                ?.takeIf { it.version == PERSISTED_VERSION }
        if (persisted == null) {
            // Nothing secret is in this document, so an unreadable one only costs the list.
            AppLog.warning(
                category = LOG_CATEGORY,
                event = "list_unreadable",
                message = "The saved file source list could not be read",
            )
            return emptyList()
        }
        return persisted.sources
            .filter { it.id.matches(VALID_ID) }
            .distinctBy { it.id }
            .take(MAX_FILE_SOURCES)
            .map {
                FileSource(
                    id = it.id,
                    kind = it.kind,
                    name = it.name.take(MAX_SOURCE_NAME_CHARS),
                    origin = it.origin,
                    rootSegments = it.rootSegments,
                    username = it.username,
                )
            }
    }

    private fun passwordKey(id: String): String = PASSWORD_KEY_PREFIX + id

    private companion object {
        const val SETTINGS_KEY = "filesources.v1"
        const val PERSISTED_VERSION = 1
        const val PASSWORD_KEY_PREFIX = "file-source-password:"
        const val MAX_FILE_SOURCES = 50
        const val MAX_PASSWORD_CHARS = 1_024
        val VALID_ID = Regex("fs[0-9a-f]{24}")
    }
}

/** `fs` and 24 hex digits: no user content, and nothing a log reader could learn from. */
internal fun randomFileSourceId(): String {
    val bytes = Random.nextBytes(ID_RANDOM_BYTES)
    return "fs" + bytes.joinToString("") { byte -> (byte.toInt() and 0xFF).toString(16).padStart(2, '0') }
}

private const val ID_RANDOM_BYTES = 12
