package com.yfuse.core.widget

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal val widgetJson =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
const val MAX_WIDGET_SOURCE_BYTES = 1_048_576
const val MAX_WIDGET_DATABASE_BYTES = 24 * MAX_WIDGET_SOURCE_BYTES

@Serializable
data class WidgetParameter(
    val name: String,
    val label: String = name,
    val type: String = "input",
    val defaultValue: JsonElement = JsonPrimitive(""),
    val required: Boolean = false,
    val options: List<WidgetChoice> = emptyList(),
    val belongTo: JsonObject? = null,
)

@Serializable
data class WidgetChoice(
    val title: String,
    val value: JsonElement,
)

@Serializable
data class WidgetModule(
    val id: String,
    val title: String,
    val functionName: String,
    val type: String = "media_list",
    val params: List<WidgetParameter> = emptyList(),
    val cacheDuration: Int = 3600,
    val timeoutSeconds: Int = 30,
)

@Serializable
data class WidgetMetadata(
    val id: String,
    val title: String,
    val version: String = "1.0.0",
    val author: String = "",
    val description: String = "",
    val modules: List<WidgetModule>,
    val icon: String = "",
    val website: String = "",
)

@Serializable
data class InstalledWidget(
    val metadata: WidgetMetadata,
    val source: String,
    val origin: String,
    val digest: String,
    val enabled: Boolean = true,
)

/** Native-only detail identity. Script JSON is never allowed to supply this target. */
@Serializable
data class WidgetLibraryTarget(
    val serverId: String,
    val itemId: String,
)

@Serializable
data class WidgetMedia(
    val id: String,
    val title: String,
    val poster: String = "",
    val backdrop: String = "",
    val description: String = "",
    val year: String = "",
    val rating: String = "",
    val mediaType: String = "movie",
    val type: String = "",
    val link: String = "",
    val videoUrl: String = "",
    val tmdbId: String = "",
    val date: String = "",
    val group: String = "",
    val progress: Float? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val headers: Map<String, String> = emptyMap(),
    val children: List<WidgetMedia> = emptyList(),
    val categoryParams: JsonObject = JsonObject(emptyMap()),
    val played: Boolean? = null,
    val available: Boolean? = null,
    val libraryTarget: WidgetLibraryTarget? = null,
    /** A confirmed next-up item inside [children], never a positional guess. */
    val nextEpisodeId: String? = null,
    val durationMinutes: Int? = null,
    /** Source-owned title metadata needed by stream resolvers, separate from browse parameters. */
    val resourceMetadata: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class WidgetSection(
    val id: String,
    val widgetId: String,
    val moduleId: String,
    val title: String,
    val style: String = "landscape",
    val params: JsonObject = JsonObject(emptyMap()),
    val limit: Int = 12,
    val hidden: Boolean = false,
    val showMetadata: Boolean = true,
    val compact: Boolean = false,
)

@Serializable
data class WidgetPage(
    val id: String,
    val title: String,
    val sections: List<WidgetSection> = emptyList(),
    val hidden: Boolean = false,
)

@Serializable
data class WidgetDatabase(
    val schema: Int = 1,
    val widgets: List<InstalledWidget> = emptyList(),
    val pages: List<WidgetPage> = emptyList(),
    val defaultPageId: String? = null,
    val bundledDigests: Map<String, String> = emptyMap(),
    val bundledStateVersion: Int = 0,
    val removedBundledWidgets: Set<String> = emptySet(),
    val removedBundledPages: Set<String> = emptySet(),
)

internal fun JsonObject.text(vararg keys: String): String =
    keys.firstNotNullOfOrNull { (get(it) as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }.orEmpty()

internal fun JsonObject.array(name: String): JsonArray = get(name) as? JsonArray ?: JsonArray(emptyList())

fun parseWidgetMetadata(
    raw: String,
    fallbackId: String,
): WidgetMetadata {
    val root = widgetJson.parseToJsonElement(raw).jsonObject

    fun parameters(value: JsonArray): List<WidgetParameter> =
        value.map { entry ->
            val p = entry.jsonObject
            val name = p.text("name")
            require(name.isNotBlank() && name.length <= 100) { "参数名称无效" }
            val defaultValue = p["defaultValue"] ?: p["value"] ?: JsonPrimitive("")
            require(defaultValue is JsonPrimitive) { "参数 $name 的默认值必须是文本、数字或布尔值" }
            WidgetParameter(
                name = name,
                label = p.text("label", "title").ifBlank { name },
                type = p.text("type").ifBlank { "input" },
                defaultValue = defaultValue,
                required = p["required"]?.jsonPrimitive?.booleanOrNull == true,
                options =
                    (p["enumOptions"] ?: p["enumValues"] ?: p["placeholders"] as? JsonArray)
                        .let { it as? JsonArray }
                        .orEmpty()
                        .map { option ->
                            if (option is JsonObject) {
                                val value = option["value"] ?: JsonPrimitive("")
                                require(value is JsonPrimitive) { "参数 $name 的选项值格式不支持" }
                                WidgetChoice(
                                    option.text("title", "label", "value"),
                                    value,
                                )
                            } else {
                                require(option is JsonPrimitive) { "参数 $name 的选项值格式不支持" }
                                WidgetChoice(option.jsonPrimitive.content, option)
                            }
                        },
                belongTo = p["belongTo"] as? JsonObject,
            )
        }
    val global = parameters(root.array("globalParams"))
    val modules =
        root.array("modules").map { entry ->
            val m = entry.jsonObject
            val function = m.text("functionName")
            require(function.matches(Regex("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*"))) { "模块函数名无效" }
            val title = m.text("title", "name")
            require(title.isNotBlank()) { "模块缺少名称" }
            WidgetModule(
                id = m.text("id").ifBlank { function },
                title = title.take(120),
                functionName = function,
                type = m.text("type").ifBlank { "media_list" },
                params = (global + parameters(m.array("params"))).associateBy { it.name }.values.toList(),
                cacheDuration = m.text("cacheDuration").toIntOrNull()?.coerceIn(0, 86400) ?: 3600,
                timeoutSeconds = m.text("timeoutSeconds").toIntOrNull()?.coerceIn(1, 60) ?: 30,
            )
        }
    require(
        modules.isNotEmpty() &&
            modules.size <= 64 &&
            modules
                .map {
                    it.id
                }.distinct()
                .size == modules.size,
    ) { "组件需包含 1–64 个不同的模块" }
    val title = root.text("title", "name")
    require(title.isNotBlank()) { "组件缺少名称" }
    val id =
        root
            .text("id")
            .ifBlank { fallbackId }
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(160)
    return WidgetMetadata(
        id,
        title.take(120),
        root.text("version").ifBlank {
            "1.0.0"
        },
        root.text("author").take(120),
        root.text("description").take(2000),
        modules,
        icon = widgetWebLink(root.text("icon", "logo", "authorIcon")),
        website = widgetWebLink(root.text("site", "website", "homepage")),
    )
}

internal fun widgetWebLink(value: String): String =
    value
        .trim()
        .takeIf { it.length <= 4096 && (it.startsWith("https://", true) || it.startsWith("http://", true)) }
        ?.let { raw ->
            runCatching { io.ktor.http.Url(raw) }
                .getOrNull()
                ?.takeIf { it.protocol.name in setOf("http", "https") && it.host.isNotBlank() }
                ?.let { raw }
        }.orEmpty()

fun parseWidgetMedia(raw: String): List<WidgetMedia> {
    val entries = widgetJson.parseToJsonElement(raw)
    require(entries is JsonArray) { "组件列表函数必须返回数组" }
    require(entries.size <= 500) { "组件单页最多返回 500 条内容" }
    return entries.mapNotNull { (it as? JsonObject)?.let { item -> parseWidgetItem(item) } }.distinctBy { it.id }
}

fun parseWidgetItem(
    item: JsonObject,
    depth: Int = 0,
): WidgetMedia {
    require(depth <= 4) { "季集数据层级过深" }

    fun image(vararg names: String): String {
        val value = item.text(*names)
        return when {
            value.startsWith("/") -> "https://image.tmdb.org/t/p/w780$value"
            value.startsWith("https://") || value.startsWith("http://") -> value
            else -> ""
        }
    }
    val title = item.text("title", "name").ifBlank { "未命名内容" }.take(300)
    val link =
        item.text("link").ifBlank {
            item
                .text("id")
                .takeIf {
                    item.text("type").equals("url", true) &&
                        (it.startsWith("https://", true) || it.startsWith("http://", true))
                }.orEmpty()
        }
    val video = item.text("videoUrl", "video_url", "url").take(16384)
    val id =
        item
            .text("id", "mediaId", "tmdbId", "tmdb_id")
            .ifBlank {
                link.ifBlank { video.ifBlank { title } }
            }.take(16384)
    val childArray =
        listOf("seasons", "episodeItems", "episodes", "playSources", "childItems", "items")
            .firstNotNullOfOrNull { (item[it] as? JsonArray)?.takeIf { a -> a.isNotEmpty() } }
    require((childArray?.size ?: 0) <= 500) { "季集条目过多" }
    return WidgetMedia(
        id = id,
        title = title,
        poster = image("posterUrl", "poster_url", "poster", "posterPath", "poster_path", "image"),
        backdrop = image("backdropUrl", "backdrop_url", "backdrop", "coverUrl", "backdropPath", "backdrop_path"),
        description = item.text("description", "overview").take(8000),
        year = item.text("year", "releaseDate", "release_date").take(10),
        rating = item.text("rating", "vote_average"),
        mediaType =
            item.text("mediaType", "media_type").ifBlank {
                if (item.text("type").lowercase() in setOf("url", "video", "live")) "video" else "movie"
            },
        type = item.text("type"),
        link = link.take(16384),
        videoUrl = video,
        tmdbId = item.text("tmdbId", "tmdb_id").ifBlank { if (item.text("type") == "tmdb") id else "" },
        date = item.text("date", "airDate", "air_date", "releaseDate", "release_date").take(32),
        group = item.text("group", "category", "genre"),
        progress =
            item
                .text("progress")
                .toFloatOrNull()
                ?.takeIf { it.isFinite() }
                ?.coerceIn(0f, 1f),
        season =
            item.text("seasonNumber", "season_number", "season").toIntOrNull()
                ?: Regex("第\\s*([0-9]+)\\s*季").find(item.text("seasonInfo"))?.groupValues?.get(1)?.toIntOrNull(),
        episode = item.text("episodeNumber", "episode_number", "episode").toIntOrNull(),
        headers =
            ((item["headers"] as? JsonObject).orEmpty() + (item["customHeaders"] as? JsonObject).orEmpty())
                .mapNotNull { (name, value) -> (value as? JsonPrimitive)?.contentOrNull?.let { name to it } }
                .toMap(),
        children =
            childArray
                .orEmpty()
                .mapNotNull {
                    (it as? JsonObject)?.let { o ->
                        parseWidgetItem(o, depth + 1)
                    }
                }.distinctBy { it.id },
        categoryParams = item["params"] as? JsonObject ?: JsonObject(emptyMap()),
        played = (item["played"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull,
        available = (item["available"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull,
        durationMinutes =
            item.text("durationMinutes", "runtimeMinutes", "runtime").toIntOrNull()?.takeIf {
                it in
                    1..1440
            },
        resourceMetadata =
            JsonObject(
                item.filterKeys {
                    it in setOf(
                        "originalTitle", "seriesName", "episodeName", "seasonInfo", "genreTitle",
                        "releaseDate", "premiereDate", "tmdbInfo",
                    )
                },
            ),
    )
}

fun WidgetModule.parameterValues(
    overrides: JsonObject,
    page: Int = 1,
): JsonObject =
    JsonObject(
        params.associate { it.name to if (it.type == "page") JsonPrimitive(page) else it.defaultValue } + overrides +
            params.filter { it.type == "page" }.associate { it.name to JsonPrimitive(page) },
    )

fun WidgetParameter.visible(values: JsonObject): Boolean {
    val condition = belongTo ?: return true
    val actual = values[condition.text("paramName")] ?: return false
    return when (val expected = condition["value"]) {
        is JsonArray -> actual in expected
        else -> actual == expected
    }
}

fun WidgetModule.parameterError(overrides: JsonObject): String? {
    val values = parameterValues(overrides)
    params.filter { it.type != "page" && it.visible(values) }.forEach { parameter ->
        val value = values[parameter.name]
        if (value != null && value !is JsonPrimitive) return "${parameter.label}格式不支持，请重新填写"
        val text = (value as? JsonPrimitive)?.contentOrNull.orEmpty()
        if (parameter.required && text.isBlank()) return "请填写${parameter.label}"
        if (text.isNotBlank() && parameter.type in setOf("number", "integer")) {
            val number = text.toDoubleOrNull()
            if (number == null || !number.isFinite()) return "${parameter.label}需为有效数字"
            if (parameter.type == "integer" && number % 1 != 0.0) return "${parameter.label}需为整数"
        }
        if (text.isNotBlank() && parameter.options.isNotEmpty() && parameter.options.none { it.value == value }) {
            return "请重新选择${parameter.label}"
        }
    }
    return null
}
