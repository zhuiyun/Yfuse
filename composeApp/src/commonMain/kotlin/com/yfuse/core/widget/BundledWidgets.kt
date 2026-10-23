package com.yfuse.core.widget

import kotlinx.coroutines.CancellationException

/** Bundled code and a default page; user-owned pages and imported scripts are never replaced. */
data class BundledWidgetSource(
    val id: String,
    val source: String,
    val page: WidgetPage,
) {
    val origin: String get() = "builtin:$id"
}

internal const val BUNDLED_TMDB_ID = "plugin_builtin_tmdb"
internal const val BUNDLED_RECOMMENDATIONS_ID = "plugin_builtin_movie_recommend"
internal val BUNDLED_WIDGET_IDS = setOf(BUNDLED_TMDB_ID, BUNDLED_RECOMMENDATIONS_ID)

/** Explicit restoration can be partial when a resource is unavailable or an imported script is incompatible. */
data class BundledWidgetRestoreResult(
    val restoredWidgets: Int,
    val restoredPages: Int,
    val unavailableNames: List<String> = emptyList(),
    val incompleteNames: List<String> = emptyList(),
)

private fun bundledPriority(id: String): Int =
    when (id) {
        BUNDLED_TMDB_ID -> 0
        BUNDLED_RECOMMENDATIONS_ID -> 1
        else -> 2
    }

/** Display ordering only: personal layouts, default pages and enablement remain user-owned. */
fun List<InstalledWidget>.withBundledWidgetsFirst(): List<InstalledWidget> =
    sortedBy {
        bundledPriority(it.metadata.id)
    }

fun List<WidgetPage>.withBundledPagesFirst(): List<WidgetPage> =
    sortedBy {
        bundledPriority(it.id.removePrefix("page-"))
    }

internal suspend fun bundledWidgetSources(read: suspend (String) -> String): List<BundledWidgetSource> =
    listOf(
        BundledWidgetSource(
            BUNDLED_TMDB_ID,
            readBundledSource("tmdb.js", read),
            bundledPage(
                BUNDLED_TMDB_ID,
                "TMDB",
                listOf("trending" to "今日趋势", "nowPlaying" to "正在热映", "popular" to "热门电影", "topRated" to "高分电影"),
            ),
        ),
        BundledWidgetSource(
            BUNDLED_RECOMMENDATIONS_ID,
            readBundledSource("movie-recommendations.js", read),
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

/** A missing resource remains identifiable and must not prevent the other preset from installing. */
private suspend fun readBundledSource(
    name: String,
    read: suspend (String) -> String,
): String =
    try {
        read(name)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // Inspection reports this preset as unavailable and leaves it eligible for a later retry.
        ""
    }

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

internal fun validateBundledWidget(
    source: BundledWidgetSource,
    widget: InstalledWidget,
) {
    require(source.id == widget.metadata.id && source.origin == widget.origin) { "预置组件信息不一致" }
    require(
        source.page.sections.all { section ->
            section.widgetId == source.id && widget.metadata.modules.any { it.id == section.moduleId }
        },
    ) { "预置页面引用了无效栏目" }
}

/** Legacy digests conflated installation history with deletion; ambiguous missing entries stay deleted. */
private fun migrateBundledState(database: WidgetDatabase): WidgetDatabase {
    if (database.bundledStateVersion >= 1) return database
    val recorded = database.bundledDigests.keys
    return database.copy(
        bundledStateVersion = 1,
        removedBundledWidgets =
            database.removedBundledWidgets + recorded.filter { id -> database.widgets.none { it.metadata.id == id } },
        removedBundledPages =
            database.removedBundledPages +
                recorded.map { "page-$it" }.filter { id -> database.pages.none { it.id == id } },
    )
}

internal fun mergeBundledWidgets(
    database: WidgetDatabase,
    bundles: List<Pair<BundledWidgetSource, InstalledWidget>>,
    restoreMissing: Boolean = false,
): WidgetDatabase {
    require(bundles.map { it.first.id }.distinct().size == bundles.size) { "预置组件 ID 重复" }
    if (bundles.isEmpty()) return database
    var updated = migrateBundledState(database)
    for ((source, widget) in bundles) {
        validateBundledWidget(source, widget)
        if (restoreMissing) {
            updated =
                updated.copy(
                    removedBundledWidgets = updated.removedBundledWidgets - source.id,
                    removedBundledPages = updated.removedBundledPages - source.page.id,
                )
        }
        val previous = updated.widgets.firstOrNull { it.metadata.id == source.id }
        val recordedDigest = updated.bundledDigests[source.id]
        val owned = previous?.origin == source.origin && previous.digest == recordedDigest
        val installMissing =
            previous == null && source.id !in updated.removedBundledWidgets
        when {
            installMissing ->
                updated =
                    updated.copy(
                        widgets = updated.widgets + widget,
                        bundledDigests = updated.bundledDigests + (source.id to widget.digest),
                    )
            owned ->
                updated =
                    updated.copy(
                        widgets =
                            updated.widgets.map {
                                if (it ==
                                    previous
                                ) {
                                    widget.copy(enabled = previous?.enabled ?: true)
                                } else {
                                    it
                                }
                            },
                        bundledDigests = updated.bundledDigests + (source.id to widget.digest),
                    )
            previous != null && recordedDigest == null ->
                updated =
                    updated.copy(
                        // Existing same-ID imports belong to the person, even if the code matches this bundle.
                        bundledDigests = updated.bundledDigests + (source.id to widget.digest),
                    )
        }
        val installed = updated.widgets.firstOrNull { it.metadata.id == source.id }
        val compatible =
            installed != null &&
                source.page.sections.all { section ->
                    installed.metadata.modules.any { it.id == section.moduleId }
                }
        if (
            compatible &&
            (installMissing || owned || restoreMissing) &&
            source.page.id !in updated.removedBundledPages &&
            updated.pages.none { it.id == source.page.id }
        ) {
            updated = updated.copy(pages = updated.pages + source.page)
        }
    }
    return updated
}
