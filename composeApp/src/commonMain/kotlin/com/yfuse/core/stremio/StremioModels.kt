package com.yfuse.core.stremio

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

@Serializable
data class StremioManifest(
    val id: String,
    val name: String,
    val version: String,
    val description: String = "",
    val types: List<String> = emptyList(),
    val resources: List<JsonElement> = emptyList(),
    val idPrefixes: List<String>? = null,
    val catalogs: List<StremioCatalog> = emptyList(),
    val behaviorHints: StremioManifestHints = StremioManifestHints(),
) {
    fun supports(
        resource: String,
        type: String,
        id: String,
    ): Boolean =
        resources.any { entry ->
            when (entry) {
                is JsonPrimitive -> entry.contentOrNull == resource && type in types && matchesPrefix(id, idPrefixes)
                is JsonObject ->
                    entry["name"] == JsonPrimitive(resource) &&
                        type in entry.stringList("types").orEmpty() &&
                        matchesPrefix(id, entry.stringList("idPrefixes"))
                else -> false
            }
        }
}

private fun matchesPrefix(
    id: String,
    prefixes: List<String>?,
): Boolean = prefixes == null || prefixes.any(id::startsWith)

private fun JsonObject.stringList(key: String): List<String>? =
    (get(key) as? kotlinx.serialization.json.JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

@Serializable
data class StremioManifestHints(
    val configurable: Boolean = false,
    val configurationRequired: Boolean = false,
)

@Serializable
data class StremioCatalog(
    val type: String,
    val id: String,
    val name: String = id,
    val extra: List<StremioExtra> = emptyList(),
    val extraSupported: List<String> = emptyList(),
    val extraRequired: List<String> = emptyList(),
) {
    val extras: List<StremioExtra> get() =
        extra.ifEmpty {
            (extraSupported + extraRequired).distinct().map { StremioExtra(it, it in extraRequired) }
        }

    fun accepts(values: Map<String, String>): Boolean =
        extras.filter { it.isRequired }.all { !values[it.name].isNullOrBlank() }
}

@Serializable
data class StremioExtra(
    val name: String,
    val isRequired: Boolean = false,
    val options: List<String> = emptyList(),
)

@Serializable
data class StremioMeta(
    val id: String,
    val type: String,
    val name: String,
    val poster: String? = null,
    val description: String? = null,
    val releaseInfo: String? = null,
    val genres: List<String> = emptyList(),
    val videos: List<StremioVideo> = emptyList(),
    val streams: List<StremioStream> = emptyList(),
)

@Serializable
data class StremioVideo(
    val id: String,
    val title: String = "",
    val season: Int? = null,
    val episode: Int? = null,
    val thumbnail: String? = null,
    val streams: List<StremioStream> = emptyList(),
) {
    val label: String get() =
        listOfNotNull(season?.let { "S$it" }, episode?.let { "第 $it 集" }, title.takeIf { it.isNotBlank() })
            .joinToString(" · ")
            .ifBlank { id }
}

@Serializable
data class StremioStream(
    val name: String = "播放源",
    val title: String? = null,
    val description: String? = null,
    val url: String? = null,
    val infoHash: String? = null,
    val ytId: String? = null,
    val externalUrl: String? = null,
    val subtitles: List<StremioSubtitle> = emptyList(),
    val behaviorHints: StremioStreamHints = StremioStreamHints(),
) {
    val unsupportedReason: String? get() =
        when {
            infoHash != null || url?.startsWith("magnet:", true) == true -> "BT／磁力资源暂不支持，请选择 HTTP 播放源"
            ytId != null -> "YouTube 专用资源暂不支持"
            externalUrl != null -> "外部网页资源暂不支持"
            !isStremioHttpUrl(url) -> "此资源没有可播放的 HTTP／HLS／DASH 地址"
            behaviorHints.proxyHeaders.isNotEmpty() -> "此播放源需要专用代理请求头，当前版本暂不支持"
            else -> null
        }
}

@Serializable
data class StremioStreamHints(
    val proxyHeaders: Map<String, JsonElement> = emptyMap(),
    val videoHash: String? = null,
    val videoSize: Long? = null,
    val filename: String? = null,
)

@Serializable
data class StremioSubtitle(
    val id: String = "",
    val url: String,
    val lang: String = "und",
)

data class StremioAddon(
    val key: String,
    val manifest: StremioManifest,
    val enabled: Boolean = true,
)

data class StremioCatalogRef(
    val addon: StremioAddon,
    val catalog: StremioCatalog,
) {
    val key: String get() = "${addon.key}:${catalog.type}:${catalog.id}"
    val label: String get() = "${addon.manifest.name} · ${catalog.name}"
}

data class StremioSource(
    val addonName: String,
    val stream: StremioStream,
)

/** Pagination counts wire entries, including malformed previews omitted from the UI. */
data class StremioCatalogPage(
    val items: List<StremioMeta>,
    val receivedCount: Int,
)

data class StremioBatch<T>(
    val values: List<T>,
    val failures: List<String> = emptyList(),
)
