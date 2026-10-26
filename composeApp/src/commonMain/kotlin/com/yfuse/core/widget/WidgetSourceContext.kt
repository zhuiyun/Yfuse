package com.yfuse.core.widget

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Carries source-specific playback and detail information alongside a resolved film identity. */
@Serializable
data class WidgetSourceContext(
    val widgetId: String,
    val media: WidgetMedia,
    val params: JsonObject = JsonObject(emptyMap()),
    /** Reopen history with the current section settings rather than persisting credentials. */
    val sectionId: String? = null,
)

/** A stream resolver can work from the item ID even when the catalog returns no link. */
fun WidgetSourceContext.hasStreamResolver(database: WidgetDatabase): Boolean =
    database.widgets.any { widget ->
        widget.enabled &&
            widget.metadata.id == widgetId &&
            widget.metadata.modules.any { it.id == "loadResource" && it.type == "stream" }
    }

/** Keep catalog identity and title metadata when resolving a particular season/episode or route. */
internal fun WidgetSourceContext.resourceArguments(
    module: WidgetModule,
    route: WidgetMedia,
): JsonObject {
    val metadata = JsonObject(media.resourceMetadata + route.resourceMetadata)
    val contentType = if (media.isWidgetFilmOrSeries()) media.mediaType else route.mediaType
    val releaseDate = metadata.text("releaseDate").ifBlank { route.year.ifBlank { media.year } }
    return JsonObject(
        module.parameterValues(params) + metadata +
            buildJsonObject {
                put("id", route.id)
                put("title", route.title)
                put("type", route.type.ifBlank { media.type })
                put("mediaType", contentType)
                put("seriesName", metadata.text("seriesName").ifBlank { media.title })
                put("link", route.link)
                put("videoUrl", route.videoUrl)
                put("tmdbId", route.tmdbId.ifBlank { media.tmdbId })
                put("releaseDate", releaseDate)
                put("premiereDate", metadata.text("premiereDate").ifBlank { releaseDate })
                (route.season ?: media.season)?.let { put("season", it) }
                (route.episode ?: media.episode)?.let { put("episode", it) }
            },
    )
}

/** History uses the current settings of its originating section, including rotated credentials. */
internal fun WidgetSourceContext.withCurrentSection(database: WidgetDatabase): WidgetSourceContext {
    if (params.isNotEmpty() || sectionId == null) return this
    val section =
        database.pages
            .flatMap { it.sections }
            .filter { section ->
                section.widgetId == widgetId && (section.id == sectionId || sectionId.startsWith("${section.id}:"))
            }.maxByOrNull { it.id.length }
    return section?.let { copy(params = it.params) } ?: this
}
