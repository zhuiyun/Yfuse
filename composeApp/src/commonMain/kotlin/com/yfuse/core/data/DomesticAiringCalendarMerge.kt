package com.yfuse.core.data

import com.yfuse.core.model.AiringEpisode
import com.yfuse.core.model.AiringScheduleAuthority
import com.yfuse.core.model.ShowOrigin

/** Published episode identities win even when a correction moved their date outside the window. */
internal fun mergeForegroundDomesticSchedules(
    discovery: DomesticAiringScheduleResult,
    published: List<AiringEpisode>,
    local: List<AiringEpisode>,
    officialSeries: (Int, String) -> List<AiringEpisode>?,
): List<AiringEpisode> {
    val retained =
        if (discovery.complete) {
            emptyList()
        } else {
            local.filter {
                it.origin == ShowOrigin.Domestic &&
                    it.scheduleAuthority == AiringScheduleAuthority.Tmdb &&
                    it.scheduleProvider == "TMDB" &&
                    !it.isMovie
            }
        }
    val discovered = mergeAiringSchedules(retained, discovery.episodes)
    val corrections =
        discovered.distinctBy(AiringEpisode::showTmdbId).flatMap {
            officialSeries(it.showTmdbId, it.showTitle).orEmpty()
        }
    return mergeAiringSchedules(discovered, published + corrections)
}
