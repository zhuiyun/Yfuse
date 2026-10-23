package com.yfuse.core.widget

/** The recorded digest also acts as a tombstone when a person uninstalls a bundled component. */
data class BundledWidgetSource(
    val id: String,
    val source: String,
    val page: WidgetPage,
) {
    val origin: String get() = "builtin:$id"
}

internal const val BUNDLED_TMDB_ID = "plugin_builtin_tmdb"
internal const val BUNDLED_RECOMMENDATIONS_ID = "plugin_builtin_movie_recommend"

internal suspend fun bundledWidgetSources(read: suspend (String) -> String): List<BundledWidgetSource> =
    listOf(
        BundledWidgetSource(
            BUNDLED_TMDB_ID,
            read("tmdb.js"),
            bundledPage(
                BUNDLED_TMDB_ID,
                "TMDB",
                listOf("trending" to "今日趋势", "nowPlaying" to "正在热映", "popular" to "热门电影", "topRated" to "高分电影"),
            ),
        ),
        BundledWidgetSource(
            BUNDLED_RECOMMENDATIONS_ID,
            read("movie-recommendations.js"),
            bundledPage(
                BUNDLED_RECOMMENDATIONS_ID,
                "影视推荐",
                listOf(
                    "recMovie" to "热门电影",
                    "recTv" to "热门剧集",
                    "recAnime" to "热门动画",
                    "recCalendar" to "番剧日历",
                    "recTrending" to "流媒体趋势",
                ),
            ),
        ),
    )

private fun bundledPage(
    widgetId: String,
    title: String,
    modules: List<Pair<String, String>>,
) = WidgetPage(
    id = "page-$widgetId",
    title = title,
    sections =
        modules.map { (id, name) ->
            WidgetSection("$widgetId-$id", widgetId, id, name, style = "poster", limit = 20)
        },
)

internal fun mergeBundledWidgets(
    database: WidgetDatabase,
    bundles: List<Pair<BundledWidgetSource, InstalledWidget>>,
): WidgetDatabase {
    require(bundles.map { it.first.id }.distinct().size == bundles.size) { "预置组件 ID 重复" }
    var updated = database
    for ((source, widget) in bundles) {
        require(source.id == widget.metadata.id && source.origin == widget.origin) { "预置组件信息不一致" }
        require(source.page.sections.all { section -> widget.metadata.modules.any { it.id == section.moduleId } }) {
            "预置页面引用了无效栏目"
        }
        val previous = updated.widgets.firstOrNull { it.metadata.id == source.id }
        val recordedDigest = updated.bundledDigests[source.id]
        if (recordedDigest == null) {
            if (previous == null && updated.widgets.size >= 24) continue
            // Existing same-ID imports belong to the person, even if their content matches this bundle.
            if (previous == null) {
                updated = updated.copy(widgets = updated.widgets + widget)
                if (updated.pages.size < 32 && updated.pages.none { it.id == source.page.id }) {
                    updated = updated.copy(pages = updated.pages + source.page)
                }
            }
            updated = updated.copy(bundledDigests = updated.bundledDigests + (source.id to widget.digest))
        } else if (previous?.origin == source.origin && previous.digest == recordedDigest) {
            updated =
                updated.copy(
                    widgets =
                        updated.widgets.map {
                            if (it ==
                                previous
                            ) {
                                widget.copy(enabled = previous.enabled)
                            } else {
                                it
                            }
                        },
                    bundledDigests = updated.bundledDigests + (source.id to widget.digest),
                )
        }
    }
    return updated
}
