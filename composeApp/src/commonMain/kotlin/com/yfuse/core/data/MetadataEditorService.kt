package com.yfuse.core.data

import com.yfuse.core.model.MediaServerKind
import com.yfuse.core.model.SavedServer
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class MetadataDraft(
    val title: String,
    val overview: String,
    val tmdbId: String = "",
)

data class EditableMetadata(
    val serverId: String,
    val itemId: String,
    val draft: MetadataDraft,
    val raw: JsonObject,
    val sectionId: String? = null,
)

data class MetadataArtwork(
    val type: String,
    val url: String,
    val label: String,
    val preview: String,
)

/** Provider writes stay on the selected server and preserve fields the editor does not expose. */
class MetadataEditorService(
    private val client: HttpClient,
) {
    suspend fun load(
        server: SavedServer,
        itemId: String,
    ): EditableMetadata {
        val plex = server.kind == MediaServerKind.Plex
        val encodedItem = itemId.encodeURLPathPart()
        val path =
            if (plex) {
                "/library/metadata/$encodedItem"
            } else {
                "/Users/${server.userId.encodeURLPathPart()}/Items/$encodedItem"
            }
        val body =
            client
                .get(server.baseUrl + path) {
                    header(if (plex) "X-Plex-Token" else "X-Emby-Token", server.accessToken)
                    accept(ContentType.Application.Json)
                }.body<JsonObject>()
        val container = if (plex) body.getValue("MediaContainer").jsonObject else body
        val item =
            if (plex) {
                (container["Metadata"] as? JsonArray)?.firstOrNull()?.jsonObject ?: error(
                    "媒体不存在",
                )
            } else {
                body
            }
        val title = item.text(if (plex) "title" else "Name").orEmpty()
        val overview = item.text(if (plex) "summary" else "Overview").orEmpty()
        val tmdb =
            if (plex) {
                ""
            } else {
                (item["ProviderIds"] as? JsonObject)
                    ?.entries
                    ?.firstOrNull {
                        it.key.equals("Tmdb", true)
                    }?.value
                    ?.jsonPrimitive
                    ?.contentOrNull
                    .orEmpty()
            }
        return EditableMetadata(
            server.id,
            itemId,
            MetadataDraft(title, overview, tmdb),
            item,
            container.text("librarySectionID") ?: item.text("librarySectionID"),
        )
    }

    suspend fun save(
        server: SavedServer,
        original: EditableMetadata,
        draft: MetadataDraft,
    ) {
        require(original.serverId == server.id)
        require(
            draft.title.isNotBlank() && draft.title.length <= 500 && draft.overview.length <= 20_000,
        ) { "标题或简介长度不正确" }
        require(draft.tmdbId.isBlank() || draft.tmdbId.all(Char::isDigit)) { "TMDB ID 必须为数字" }
        val latest = load(server, original.itemId)
        check(latest.draft == original.draft) { "服务器内容已被其他操作修改，请关闭编辑器后重新打开" }
        if (server.kind == MediaServerKind.Plex) {
            val section = latest.sectionId ?: error("此 Plex 媒体缺少库信息，无法编辑")
            val type =
                when (latest.raw.text("type")) {
                    "movie" -> 1
                    "show" -> 2
                    "season" -> 3
                    "episode" -> 4
                    else -> error("此 Plex 媒体类型暂不支持编辑")
                }
            client.put("${server.baseUrl}/library/sections/${section.encodeURLPathPart()}/all") {
                header("X-Plex-Token", server.accessToken)
                parameter("id", original.itemId)
                parameter("type", type)
                parameter("title.value", draft.title.trim())
                parameter("title.locked", 1)
                parameter("summary.value", draft.overview)
                parameter("summary.locked", 1)
            }
        } else {
            val values = latest.raw.toMutableMap()
            values["Name"] = JsonPrimitive(draft.title.trim())
            values["Overview"] = JsonPrimitive(draft.overview)
            val providers = (values["ProviderIds"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
            providers.keys.filter { it.equals("Tmdb", true) }.forEach(providers::remove)
            if (draft.tmdbId.isNotBlank()) providers["Tmdb"] = JsonPrimitive(draft.tmdbId)
            values["ProviderIds"] = JsonObject(providers)
            client.post("${server.baseUrl}/Items/${original.itemId.encodeURLPathPart()}") {
                header("X-Emby-Token", server.accessToken)
                contentType(ContentType.Application.Json)
                setBody(JsonObject(values))
            }
        }
    }

    suspend fun artwork(
        server: SavedServer,
        itemId: String,
        type: String,
    ): List<MetadataArtwork> {
        require(type == "Primary" || type == "Backdrop")
        val plex = server.kind == MediaServerKind.Plex
        val encodedItem = itemId.encodeURLPathPart()
        val path =
            if (plex) {
                val category = if (type == "Primary") "posters" else "arts"
                "/library/metadata/$encodedItem/$category"
            } else {
                "/Items/$encodedItem/RemoteImages"
            }
        val body =
            client
                .get(server.baseUrl + path) {
                    header(if (plex) "X-Plex-Token" else "X-Emby-Token", server.accessToken)
                    accept(ContentType.Application.Json)
                    if (!plex) {
                        parameter("Type", type)
                        parameter("Limit", 30)
                    }
                }.body<JsonObject>()
        val values = if (plex) body["MediaContainer"]?.jsonObject?.get("Metadata") else body["Images"]
        return (values as? JsonArray).orEmpty().take(30).mapNotNull { value ->
            val item = value.jsonObject
            val url = item.text(if (plex) "ratingKey" else "Url") ?: return@mapNotNull null
            val preview = item.text(if (plex) "thumb" else "Url") ?: item.text("key") ?: url
            MetadataArtwork(type, url, item.text(if (plex) "provider" else "ProviderName") ?: "图片", preview)
        }
    }

    suspend fun selectArtwork(
        server: SavedServer,
        itemId: String,
        artwork: MetadataArtwork,
    ) {
        require(artwork.type == "Primary" || artwork.type == "Backdrop")
        if (server.kind == MediaServerKind.Plex) {
            val category = if (artwork.type == "Primary") "poster" else "art"
            client.put("${server.baseUrl}/library/metadata/${itemId.encodeURLPathPart()}/$category") {
                header("X-Plex-Token", server.accessToken)
                parameter("url", artwork.url)
            }
        } else {
            client.post("${server.baseUrl}/Items/${itemId.encodeURLPathPart()}/RemoteImages/Download") {
                header("X-Emby-Token", server.accessToken)
                parameter("Type", artwork.type)
                parameter("ImageUrl", artwork.url)
            }
        }
    }
}

private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
