package com.yfuse.core.model

/**
 * TMDB genre ids to the names this app shows.
 *
 * List responses carry `genre_ids` and never the names — those come from a separate
 * `/genre/*/list` call per language. The ids are a fixed, documented set that has not changed
 * in years, so a table is one lookup instead of a network round trip on a screen that already
 * has the data it needs to draw.
 *
 * Movie and TV ids share one map: where the two lists overlap they agree (16 動畫, 35 喜剧,
 * 18 剧情 …), and where they do not they do not collide.
 */
object TmdbGenres {
    private val names: Map<Int, String> = mapOf(
        // Movie list
        28 to "动作",
        12 to "冒险",
        16 to "动画",
        35 to "喜剧",
        80 to "犯罪",
        99 to "纪录",
        18 to "剧情",
        10751 to "家庭",
        14 to "奇幻",
        36 to "历史",
        27 to "恐怖",
        10402 to "音乐",
        9648 to "悬疑",
        10749 to "爱情",
        878 to "科幻",
        10770 to "电视电影",
        53 to "惊悚",
        10752 to "战争",
        37 to "西部",
        // TV-only additions
        10759 to "动作冒险",
        10762 to "儿童",
        10763 to "新闻",
        10764 to "真人秀",
        10765 to "科幻奇幻",
        10766 to "肥皂剧",
        10767 to "脱口秀",
        10768 to "战争政治",
    )

    fun nameOf(id: Int): String? = names[id]

    /**
     * The first [limit] recognised genres. TMDB orders them by relevance to the title, so the
     * leading one or two are what a person would say the film is; the rest are shading, and a
     * hero caption has one line.
     */
    fun labelFor(ids: List<Int>, limit: Int = 1): String? = ids
        .mapNotNull(::nameOf)
        .take(limit)
        .joinToString(" ")
        .takeIf { it.isNotBlank() }
}
