package com.yfuse.core.stremio

import com.yfuse.core.security.SECURE_STORE_MAX_VALUE_BYTES
import com.yfuse.core.security.SecureStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.util.UUID

@Serializable
private data class InstalledStremioAddon(
    val key: String,
    val url: String,
    val manifest: StremioManifest,
    val enabled: Boolean = true,
) {
    fun publicInfo() = StremioAddon(key, manifest, enabled)
}

/** One adult profile's plugins. The entire configured URL stays in the encrypted store. */
class StremioRepository(
    private val client: StremioClient,
    private val secureStore: SecureStore,
    profileId: String,
) {
    private val storageKey = "stremio.addons.$profileId"
    private val mutex = Mutex()
    private val requests = Semaphore(4)
    private var entries = emptyList<InstalledStremioAddon>()
    private var initialized = false
    private val mutableAddons = MutableStateFlow<List<StremioAddon>>(emptyList())
    val addons = mutableAddons.asStateFlow()

    suspend fun load() =
        withContext(Dispatchers.Default) {
            mutex.withLock {
                if (initialized) return@withLock
                val bytes = secureStore.get(storageKey)
                val restored =
                    try {
                        bytes
                            ?.let {
                                stremioJson.decodeFromString<List<InstalledStremioAddon>>(
                                    it.decodeToString(),
                                )
                            }.orEmpty()
                    } finally {
                        bytes?.fill(0)
                    }
                require(restored.size <= 32) { "保存的插件数量超出限制" }
                entries = restored
                initialized = true
                publish()
            }
        }

    suspend fun inspect(url: String): StremioManifest = client.manifest(url)

    suspend fun refresh(key: String) {
        val entry = mutex.withLock { entries.firstOrNull { it.key == key } } ?: return
        val manifest = client.manifest(entry.url)
        require(!manifest.behaviorHints.configurationRequired) { "请重新配置此插件" }
        mutate { old -> old.map { if (it.key == key) it.copy(manifest = manifest) else it } }
    }

    suspend fun install(
        url: String,
        manifest: StremioManifest,
    ) = mutate { old ->
        val normalized = normalizeStremioManifestUrl(url)
        require(!manifest.behaviorHints.configurationRequired) { "请先完成插件配置，再粘贴配置后的安装地址" }
        val previous = old.firstOrNull { it.url == normalized }
        require(previous != null || old.size < 32) { "最多安装 32 个插件" }
        val next = InstalledStremioAddon(previous?.key ?: UUID.randomUUID().toString(), normalized, manifest)
        if (previous == null) old + next else old.map { if (it.key == previous.key) next else it }
    }

    suspend fun setEnabled(
        key: String,
        enabled: Boolean,
    ) = mutate { old ->
        old.map {
            if (it.key ==
                key
            ) {
                it.copy(enabled = enabled)
            } else {
                it
            }
        }
    }

    suspend fun remove(key: String) = mutate { old -> old.filterNot { it.key == key } }

    private suspend fun mutate(transform: (List<InstalledStremioAddon>) -> List<InstalledStremioAddon>) =
        withContext(Dispatchers.Default) {
            mutex.withLock {
                check(initialized)
                val updated = transform(entries)
                val bytes = stremioJson.encodeToString(updated).encodeToByteArray()
                try {
                    require(bytes.size <= SECURE_STORE_MAX_VALUE_BYTES) { "插件配置总量过大，请移除不使用的插件" }
                    secureStore.put(storageKey, bytes)
                } finally {
                    bytes.fill(0)
                }
                entries = updated
                publish()
            }
        }

    private fun publish() {
        mutableAddons.value = entries.map { it.publicInfo() }
    }

    private suspend fun enabledEntries(): List<InstalledStremioAddon> = mutex.withLock { entries.filter { it.enabled } }

    suspend fun catalogs(): List<StremioCatalogRef> =
        enabledEntries().flatMap { entry ->
            entry.manifest.catalogs
                .distinctBy { it.type to it.id }
                .map { StremioCatalogRef(entry.publicInfo(), it) }
        }

    suspend fun catalog(
        ref: StremioCatalogRef,
        extras: Map<String, String>,
    ): StremioCatalogPage {
        val entry = enabledEntries().firstOrNull { it.key == ref.addon.key } ?: error("插件已停用或移除")
        return client.catalog(entry.url, ref.catalog, extras)
    }

    suspend fun meta(preview: StremioMeta): StremioBatch<StremioMeta> =
        collect("meta", preview.type, preview.id) { entry ->
            listOfNotNull(client.meta(entry.url, preview.type, preview.id))
        }

    suspend fun streams(
        type: String,
        videoId: String,
    ): StremioBatch<StremioSource> =
        collect("stream", type, videoId) { entry ->
            client.streams(entry.url, type, videoId).map { StremioSource(entry.manifest.name, it) }
        }

    suspend fun subtitles(
        type: String,
        videoId: String,
        stream: StremioStream,
    ): StremioBatch<StremioSubtitle> {
        val hints = stream.behaviorHints
        val extra =
            buildMap {
                hints.videoHash?.let { put("videoHash", it) }
                hints.videoSize?.let { put("videoSize", it.toString()) }
                hints.filename?.let { put("filename", it) }
            }
        return collect("subtitles", type, videoId) { entry -> client.subtitles(entry.url, type, videoId, extra) }
    }

    private suspend fun <T> collect(
        resource: String,
        type: String,
        id: String,
        fetch: suspend (InstalledStremioAddon) -> List<T>,
    ): StremioBatch<T> =
        coroutineScope {
            val results =
                enabledEntries()
                    .filter { it.manifest.supports(resource, type, id) }
                    .map { entry ->
                        async {
                            requests.withPermit {
                                try {
                                    StremioBatch(fetch(entry))
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    StremioBatch<T>(emptyList(), listOf("${entry.manifest.name}：请求失败，可重试"))
                                }
                            }
                        }
                    }.awaitAll()
            StremioBatch(results.flatMap { it.values }, results.flatMap { it.failures })
        }
}
