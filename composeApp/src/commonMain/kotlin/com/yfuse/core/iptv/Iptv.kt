package com.yfuse.core.iptv

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.random.Random

const val MAX_M3U_BYTES = 4 * 1024 * 1024
const val MAX_IPTV_CHANNELS = 5000
internal const val MAX_IPTV_STORAGE_BYTES = 16 * 1024 * 1024

@Serializable
data class IptvChannel(
    val id: String,
    val name: String,
    val url: String,
    val group: String = "未分组",
    val logo: String = "",
    val headers: Map<String, String> = emptyMap(),
) {
    override fun toString() = "IptvChannel(id=$id)"
}

@Serializable
data class IptvSubscription(
    val id: String,
    val name: String,
    val sourceUrl: String? = null,
    val channels: List<IptvChannel> = emptyList(),
    val favorites: Set<String> = emptySet(),
    val updatedAtEpochMs: Long = 0,
    val skipped: Int = 0,
) {
    override fun toString() = "IptvSubscription(id=$id, channels=${channels.size})"
}

data class M3uResult(
    val channels: List<IptvChannel>,
    val skipped: Int,
)

/** Extended M3U lists, not HLS media manifests. No network work or playlist execution. */
fun parseM3u(text: String): M3uResult {
    require(text.encodeToByteArray().size <= MAX_M3U_BYTES) { "订阅超过 4 MB" }
    var title = ""
    var group = "未分组"
    var logo = ""
    var tvgId = ""
    var headers = mutableMapOf<String, String>()
    var skipped = 0
    var sawHeader = false
    val channels = linkedMapOf<String, IptvChannel>()
    text.removePrefix("\uFEFF").lineSequence().forEach { raw ->
        require(raw.length <= 8192) { "订阅含过长行" }
        val line = raw.trim()
        when {
            line.isEmpty() -> Unit
            line.startsWith("#EXTM3U") -> sawHeader = true
            line.startsWith("#EXT-X-") -> throw IllegalArgumentException("这是单路 HLS 播放清单，请导入包含频道的 M3U 订阅")
            line.startsWith("#EXTINF:") -> {
                var quoted = false
                val comma =
                    line.indexOfFirst { char ->
                        if (char == '"') quoted = !quoted
                        char == ',' && !quoted
                    }
                title = if (comma < 0) "" else line.substring(comma + 1).trim()
                val attributes =
                    Regex("([A-Za-z0-9_-]+)=\"([^\"]*)\"")
                        .findAll(
                            if (comma <
                                0
                            ) {
                                line
                            } else {
                                line.take(comma)
                            },
                        ).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                group = attributes["group-title"]?.takeIf(String::isNotBlank) ?: "未分组"
                logo = attributes["tvg-logo"].orEmpty()
                tvgId = attributes["tvg-id"].orEmpty()
                if (title.isBlank()) title = attributes["tvg-name"].orEmpty()
                headers = mutableMapOf()
            }
            line.startsWith("#EXTGRP:") -> group = line.substringAfter(':').trim().ifBlank { "未分组" }
            line.startsWith("#EXTVLCOPT:") -> {
                val key = line.substringAfter(':').substringBefore('=').lowercase()
                val name =
                    when (key) {
                        "http-user-agent" -> "User-Agent"
                        "http-referrer", "http-referer" -> "Referer"
                        else -> null
                    }
                val value = line.substringAfter('=', "")
                if (name != null && value.none(Char::isISOControl)) headers[name] = value
            }
            line.startsWith('#') -> Unit
            else -> {
                if (title.isBlank() || !isIptvHttpUrl(line)) {
                    skipped++
                } else {
                    val identity = if (tvgId.isNotBlank()) "tvg:$tvgId" else group + "\u0000" + title
                    val id = identity.fold(1125899906842597L) { hash, char -> hash * 31 + char.code }.toString(16)
                    require(channels.size < MAX_IPTV_CHANNELS || id in channels) { "订阅超过 5000 个频道" }
                    channels[id] = IptvChannel(id, title, line, group, logo, headers.toMap())
                }
                title = ""
                headers = mutableMapOf()
                tvgId = ""
            }
        }
    }
    require(sawHeader && channels.isNotEmpty()) { "没有有效频道：需包含 EXTINF 与 HTTP/HTTPS 播放地址" }
    return M3uResult(channels.values.toList(), skipped)
}

fun isIptvHttpUrl(value: String): Boolean =
    (value.startsWith("http://", true) || value.startsWith("https://", true)) &&
        value.substringAfter("://").substringBefore('/').isNotBlank() &&
        value.none { it.isISOControl() || it == ' ' }

interface IptvStorage {
    fun read(): String?

    fun write(text: String)
}

expect fun createIptvStorage(): IptvStorage

expect suspend fun downloadM3u(url: String): String

/** Refresh commits a fully parsed replacement only. Failure/cancellation leave old channels intact. */
class IptvRepository(
    private val storage: IptvStorage,
    private val download: suspend (String) -> String = ::downloadM3u,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(IptvSubscription.serializer())
    private var loaded = false
    private val _subscriptions = MutableStateFlow<List<IptvSubscription>>(emptyList())
    val subscriptions = _subscriptions.asStateFlow()

    suspend fun ensureLoaded() =
        mutex.withLock {
            if (!loaded) {
                val data =
                    withContext(dispatcher) { storage.read()?.let { json.decodeFromString(serializer, it) }.orEmpty() }
                require(data.size <= 32 && data.sumOf { it.channels.size } <= 20_000) { "订阅存储超过预算" }
                _subscriptions.value = data
                loaded = true
            }
        }

    suspend fun add(
        name: String,
        url: String? = null,
        text: String? = null,
    ): String {
        ensureLoaded()
        require(name.isNotBlank() && name.length <= 100) { "请填写订阅名称（最多 100 字）" }
        require(url == null || isIptvHttpUrl(url)) { "请输入 HTTP 或 HTTPS 订阅地址" }
        val parsed = withContext(dispatcher) { parseM3u(text ?: download(requireNotNull(url))) }
        val id = now().toString(16) + Random.nextLong().toString(16)
        change { current ->
            current +
                IptvSubscription(
                    id,
                    name.trim(),
                    url,
                    parsed.channels,
                    updatedAtEpochMs = now(),
                    skipped = parsed.skipped,
                )
        }
        return id
    }

    suspend fun refresh(id: String) {
        ensureLoaded()
        val previous = subscriptions.value.firstOrNull { it.id == id } ?: return
        val url = previous.sourceUrl ?: throw IllegalArgumentException("本地导入没有订阅地址，请重新导入")
        val parsed = withContext(dispatcher) { parseM3u(download(url)) }
        currentCoroutineContext().ensureActive()
        change { current ->
            current.map {
                if (it.id == id &&
                    it.sourceUrl == url
                ) {
                    it.copy(channels = parsed.channels, updatedAtEpochMs = now(), skipped = parsed.skipped)
                } else {
                    it
                }
            }
        }
    }

    suspend fun toggleFavorite(
        id: String,
        channelId: String,
    ) = change { current ->
        current.map {
            if (it.id != id ||
                it.channels.none { channel -> channel.id == channelId }
            ) {
                it
            } else {
                it.copy(
                    favorites =
                        if (channelId in
                            it.favorites
                        ) {
                            it.favorites - channelId
                        } else {
                            it.favorites + channelId
                        },
                )
            }
        }
    }

    suspend fun remove(id: String) = change { current -> current.filterNot { it.id == id } }

    private suspend fun change(transform: (List<IptvSubscription>) -> List<IptvSubscription>) {
        ensureLoaded()
        mutex.withLock {
            val next = transform(subscriptions.value)
            require(next.size <= 32 && next.sumOf { it.channels.size } <= 20_000) { "最多 32 个订阅、合计 20000 个频道" }
            withContext(dispatcher) {
                currentCoroutineContext().ensureActive()
                val encoded = json.encodeToString(serializer, next)
                require(encoded.encodeToByteArray().size <= MAX_IPTV_STORAGE_BYTES) { "订阅总大小超过 16 MB" }
                storage.write(encoded)
            }
            _subscriptions.value = next
        }
    }
}
