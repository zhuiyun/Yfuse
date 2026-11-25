package com.yfuse.core.data

import com.russhwolf.settings.Settings
import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.Url
import io.ktor.http.isSuccess
import io.ktor.utils.io.readBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal const val LIANGYOU_ICON_CATALOG =
    "https://raw.githubusercontent.com/MaddestAlistar/LiangYou-IconLibrary/main/LiangYou-Emby-Icons.json"
private const val MAX_CATALOG_BYTES = 4 * 1024 * 1024

internal data class ServerCatalogIcon(
    val name: String,
    val url: String,
) {
    val normalizedName = compactIconName(name)
    val wordName = spacedIconName(name)
    val matchKey = iconMatchKey(normalizedName)
}

internal fun normalizeServerIconUrl(input: String?): String? {
    val raw = input?.trim() ?: return null
    if (raw.length > 4096 || !raw.startsWith("https://", ignoreCase = true)) return null
    val authority = raw.substring(8).takeWhile { it != '/' && it != '?' && it != '#' }
    if (authority.isBlank() ||
        authority.any { it.isWhitespace() || it == '@' } ||
        raw.any { it.isISOControl() || it == '\\' }
    ) {
        return null
    }
    // Some real catalog entries use spaces in image filenames.
    return runCatching { Url(raw.replace(" ", "%20")) }.getOrNull()?.takeIf { it.host.isNotBlank() }?.toString()
}

internal fun parseServerIconCatalog(text: String): List<ServerCatalogIcon> {
    val root = Json.parseToJsonElement(text) as? JsonObject
    val icons = root?.get("icons") as? JsonArray ?: error("图标库缺少 icons 列表")
    val result =
        icons
            .mapNotNull { entry ->
                val obj = entry as? JsonObject ?: return@mapNotNull null
                val name =
                    (obj["name"] as? JsonPrimitive)
                        ?.takeIf { it.isString }
                        ?.content
                        ?.trim()
                        .orEmpty()
                val url = (obj["url"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (name.isEmpty()) return@mapNotNull null
                normalizeServerIconUrl(url)?.let { ServerCatalogIcon(name, it) }
            }.distinctBy { it.url }
    require(result.isNotEmpty()) { "图标库没有可用图片" }
    return result
}

internal fun parseCatalogSources(input: String): List<String> =
    input
        .lineSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .mapIndexed { index, line ->
            requireNotNull(normalizeServerIconUrl(line)) { "第 ${index + 1} 个链接不是有效的 HTTPS 地址" }
        }.distinct()
        .toList()

internal data class ServerIconCatalogState(
    val sources: List<String>,
    val icons: List<ServerCatalogIcon> = emptyList(),
    val failedSources: List<String> = emptyList(),
    val loading: Boolean = sources.isNotEmpty(),
)

internal class ServerIconCatalog(
    private val http: HttpClient,
    private val settings: Settings,
) {
    private data class Request(
        val sources: List<String>,
        val refresh: Long = 0,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val requests =
        MutableStateFlow(
            Request(
                settings
                    .getStringOrNull(
                        SOURCES_KEY,
                    )?.lineSequence()
                    ?.mapNotNull(::normalizeServerIconUrl)
                    ?.distinct()
                    ?.toList()
                    ?: listOf(LIANGYOU_ICON_CATALOG),
            ),
        )
    val sources: List<String> get() = requests.value.sources
    private val cache = mutableMapOf<String, List<ServerCatalogIcon>>()
    val state =
        requests
            .transformLatest { request ->
                val urls = request.sources
                cache.keys.retainAll(urls.toSet())

                suspend fun snapshot(
                    loading: Boolean,
                    failures: List<String> = emptyList(),
                ) {
                    emit(ServerIconCatalogState(urls, urls.flatMap { cache[it].orEmpty() }, failures, loading))
                }
                snapshot(loading = urls.isNotEmpty())
                val pending = urls.filter { request.refresh != 0L || it !in cache }
                val downloads = Semaphore(4)
                val results =
                    coroutineScope {
                        pending
                            .map { url ->
                                async { url to downloads.withPermit { fetch(url) } }
                            }.awaitAll()
                    }
                for ((url, icons) in results) if (icons != null) cache[url] = icons
                snapshot(loading = false, failures = results.filter { it.second == null }.map { it.first })
            }.stateIn(scope, SharingStarted.Lazily, ServerIconCatalogState(sources))

    fun saveSources(input: String) {
        val urls = parseCatalogSources(input)
        settings.putString(SOURCES_KEY, urls.joinToString("\n"))
        requests.update { if (it.sources == urls) it else Request(urls) }
    }

    fun refresh() = requests.update { it.copy(refresh = it.refresh + 1) }

    fun close() = scope.cancel()

    private suspend fun fetch(url: String): List<ServerCatalogIcon>? =
        try {
            http.prepareGet(url).execute { response ->
                check(response.status.isSuccess()) { "图标库请求失败" }
                val bytes = response.bodyAsChannel().readBuffer(MAX_CATALOG_BYTES + 1L).readByteArray()
                require(bytes.size <= MAX_CATALOG_BYTES) { "图标库文件过大" }
                parseServerIconCatalog(bytes.decodeToString())
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }

    private companion object {
        const val SOURCES_KEY = "server.iconCatalog.sources.v1"
    }
}
