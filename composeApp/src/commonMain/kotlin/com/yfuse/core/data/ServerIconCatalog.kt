package com.yfuse.core.data

import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.Url
import io.ktor.http.isSuccess
import io.ktor.utils.io.readBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
)

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

internal class ServerIconCatalog(
    private val http: HttpClient,
) {
    private val mutex = Mutex()
    private var cached: List<ServerCatalogIcon>? = null

    suspend fun load(): List<ServerCatalogIcon> =
        mutex.withLock {
            cached ?: http
                .prepareGet(LIANGYOU_ICON_CATALOG)
                .execute { response ->
                    check(response.status.isSuccess()) { "图标库请求失败" }
                    val bytes = response.bodyAsChannel().readBuffer(MAX_CATALOG_BYTES + 1L).readByteArray()
                    require(bytes.size <= MAX_CATALOG_BYTES) { "图标库文件过大" }
                    withContext(Dispatchers.Default) { parseServerIconCatalog(bytes.decodeToString()) }
                }.also { cached = it }
        }
}
