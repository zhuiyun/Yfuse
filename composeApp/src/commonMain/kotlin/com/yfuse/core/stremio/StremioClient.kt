package com.yfuse.core.stremio

import com.yfuse.core.network.embyHttpEngine
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

internal val stremioJson =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

/** Only fixed, credential-free descriptions may cross into the UI. */
internal class StremioRequestException(
    message: String,
) : IllegalArgumentException(message)

/** An anonymous client: no account token, device id, cookies or Emby interceptors. */
fun createStremioHttpClient(): HttpClient =
    HttpClient(embyHttpEngine()) {
        followRedirects = false
        install(HttpTimeout) {
            requestTimeoutMillis = 15_000
            connectTimeoutMillis = 8_000
            socketTimeoutMillis = 12_000
        }
    }

class StremioClient(
    private val http: HttpClient,
) {
    suspend fun manifest(url: String): StremioManifest {
        val manifest =
            stremioJson.decodeFromJsonElement(
                StremioManifest.serializer(),
                read(normalizeStremioManifestUrl(url)),
            )
        require(
            manifest.id.isNotBlank() &&
                manifest.name.isNotBlank() &&
                manifest.version.isNotBlank() &&
                manifest.resources.isNotEmpty(),
        ) {
            "插件清单缺少名称、版本或资源声明"
        }
        require(manifest.catalogs.size <= 100) { "插件目录数量过多" }
        return manifest
    }

    suspend fun catalog(
        url: String,
        catalog: StremioCatalog,
        extras: Map<String, String>,
    ): StremioCatalogPage {
        require(catalog.accepts(extras)) { "请先填写目录要求的筛选条件" }
        val body = read(stremioResourceUrl(url, "catalog", catalog.type, catalog.id, extras))
        val receivedCount = (body["metas"] as? JsonArray)?.size?.coerceAtMost(1_000) ?: error("插件响应缺少 metas 列表")
        val items =
            decodeList<StremioMeta>(body, "metas").filter {
                it.id.isNotBlank() &&
                    it.name.isNotBlank() &&
                    it.type.isNotBlank()
            }
        return StremioCatalogPage(items, receivedCount)
    }

    suspend fun meta(
        url: String,
        type: String,
        id: String,
    ): StremioMeta? {
        val value = read(stremioResourceUrl(url, "meta", type, id))["meta"] as? JsonObject ?: return null
        return stremioJson.decodeFromJsonElement(StremioMeta.serializer(), value).takeIf {
            it.id == id &&
                it.type == type
        }
    }

    suspend fun streams(
        url: String,
        type: String,
        id: String,
    ): List<StremioStream> = decodeList(read(stremioResourceUrl(url, "stream", type, id)), "streams")

    suspend fun subtitles(
        url: String,
        type: String,
        id: String,
        extras: Map<String, String>,
    ): List<StremioSubtitle> =
        decodeList<StremioSubtitle>(read(stremioResourceUrl(url, "subtitles", type, id, extras)), "subtitles")
            .filter { isStremioHttpUrl(it.url) }

    private suspend fun read(url: String): JsonObject =
        http.prepareGet(url).execute { response ->
            if (!response.status.isSuccess()) throw StremioRequestException("插件请求失败（HTTP ${response.status.value}）")
            val bytes = response.bodyAsChannel().readRemaining(MAX_RESPONSE_BYTES + 1L).readByteArray()
            if (bytes.size > MAX_RESPONSE_BYTES) throw StremioRequestException("插件响应过大")
            stremioJson.parseToJsonElement(bytes.decodeToString()) as? JsonObject ?: error("插件响应不是 JSON 对象")
        }

    private inline fun <reified T> decodeList(
        body: JsonObject,
        key: String,
    ): List<T> {
        val values = body[key] as? JsonArray ?: error("插件响应缺少 $key 列表")
        return values.take(1_000).mapNotNull { runCatching { stremioJson.decodeFromJsonElement<T>(it) }.getOrNull() }
    }
}

private const val MAX_RESPONSE_BYTES = 2 * 1_024 * 1_024
