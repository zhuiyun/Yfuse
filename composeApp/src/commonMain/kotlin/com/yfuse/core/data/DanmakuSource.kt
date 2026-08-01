package com.yfuse.core.data

import kotlin.random.Random
import kotlinx.serialization.Serializable

/**
 * One place 弹幕 can come from, kept by name because there is usually more than one.
 *
 * People run several of these — a public dandanplay mirror, a self-hosted one, a friend's —
 * and they disagree: a show one has indexed under its 2021 film the other has as a 2026
 * series, and neither is wrong. A single stored link made that a settings edit every time;
 * a named list makes it a chip.
 *
 * Two shapes are allowed and the URL tells them apart:
 *
 * - **A template** — carries `{id}` / `{title}` / `{season}` / `{episode}` / `{serverId}`
 *   and resolves to one file per entry. Nothing to search: the address *is* the answer.
 * - **An API root** — a dandanplay-compatible server (`/api/v2/search/anime`,
 *   `/api/v2/bangumi/{id}`, `/api/v2/comment/{id}`). This is what 搜索弹幕 talks to, and
 *   what can match an episode on its own.
 */
@Serializable
data class DanmakuSource(
    val id: String,
    val name: String,
    val url: String,
) {
    val isTemplate: Boolean get() = url.contains('{')

    /** Only an API root can be searched; a template has no index behind it. */
    val supportsSearch: Boolean get() = !isTemplate && url.isNotBlank()

    companion object {
        /** Long enough not to collide across a list a person types by hand. */
        fun newId(): String = "dms-" + Random.nextLong(1L, Long.MAX_VALUE).toString(36)
    }
}

/**
 * The episode a media entry has been pinned to, once someone has picked one by hand.
 *
 * Automatic matching is a guess made from a filename, and it is wrong often enough that a
 * correction has to outlive the player. Stored per entry, so fixing 第4集 doesn't disturb
 * 第5集 — each episode of a show is matched separately anyway.
 */
@Serializable
data class DanmakuBinding(
    val sourceId: String,
    val episodeId: String,
    /** `九门(2026)【电视剧】- 第4集`, shown in the 弹幕 panel so a bad match is visible. */
    val label: String,
)

/** One 作品 in a 搜索弹幕 result list. */
data class DanmakuSearchResult(
    val animeId: String,
    val title: String,
    /** `电影` / `电视剧`, as the server describes it. Null when it doesn't. */
    val typeLabel: String? = null,
    val episodeCount: Int? = null,
    val year: String? = null,
) {
    /** `电影 · 1 集 · 2021` — the line under the title. */
    val subtitle: String
        get() = listOfNotNull(
            typeLabel,
            episodeCount?.takeIf { it > 0 }?.let { "$it 集" },
            year,
        ).joinToString(" · ")
}

/** One 集 under a [DanmakuSearchResult], and the thing comments are actually fetched for. */
data class DanmakuEpisode(
    val episodeId: String,
    val title: String,
    val animeTitle: String? = null,
    /** As the server numbers it — used to line an automatic match up with what is playing. */
    val number: String? = null,
) {
    val label: String
        get() = listOfNotNull(animeTitle?.takeIf { it.isNotBlank() }, title.takeIf { it.isNotBlank() })
            .joinToString(" - ")
            .ifBlank { title }
}

/**
 * What a hand-picked match is filed under, so it survives changing servers.
 *
 * Keyed on the *show and its coordinate* rather than on the library's item id. The same
 * episode on two Emby servers has two different ids and nothing else about it differs;
 * matching 第4集 by hand on one server and then watching it on the other would mean doing
 * the work twice for the same file. What actually identifies the episode across both is the
 * show's name plus its season and episode number, which is also exactly what the 弹幕 index
 * is keyed on.
 *
 * Films fall back to their title. A title collision would mean two unrelated films with the
 * same name in the same collection of servers — possible, far rarer than the id churn this
 * avoids, and the cost is one wrong match that 取消匹配 undoes.
 *
 * The id remains the last resort for anything with neither, and old bindings written under
 * the id alone are still read — see [DanmakuPreferences.binding].
 */
fun danmakuBindingKey(
    itemId: String,
    title: String,
    seriesName: String? = null,
    seasonNumber: Int? = null,
    episodeNumber: Int? = null,
): String {
    val show = seriesName?.normalizedForMatching()
    if (show != null && episodeNumber != null) {
        return "s:$show|${seasonNumber ?: 1}|$episodeNumber"
    }
    val film = title.normalizedForMatching()
    return if (film != null) "m:$film" else "i:$itemId"
}

/**
 * Case, spacing and punctuation are where the same title differs between two libraries;
 * everything that survives all three is what the two copies genuinely share.
 */
private fun String.normalizedForMatching(): String? = lowercase()
    .filter { it.isLetterOrDigit() }
    .takeIf { it.isNotEmpty() }

/**
 * The source a chip row would show as selected.
 *
 * Falls back to the first entry rather than to nothing: a stored id can name a source that
 * has since been deleted, and having one link configured should never read as having none.
 */
fun List<DanmakuSource>.activeOr(id: String?): DanmakuSource? =
    firstOrNull { it.id == id } ?: firstOrNull()
