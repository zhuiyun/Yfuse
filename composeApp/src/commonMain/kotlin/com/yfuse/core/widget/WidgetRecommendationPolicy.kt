package com.yfuse.core.widget

/** Only recommendation presentations rotate; ranked, chronological and playback rows stay ordered. */
internal fun WidgetSection.recommendationLimit(): Int? {
    val style = WidgetStyle.fromId(style)
    val recommended = style.group in setOf("焦点推荐", "空间片廊")
    val source = (moduleId + " " + params.values.joinToString(" ")).lowercase()
    val ordered =
        listOf("top", "rank", "chart", "calendar", "weekday", "best", "榜", "排名")
            .any { it in source }
    return limit.coerceIn(1, 100).takeIf { recommended && !ordered }
}
