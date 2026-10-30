package com.yfuse.feature.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import com.yfuse.core.designsystem.LivingPosterHeroState
import com.yfuse.core.designsystem.rememberLivingPosterHeroState
import com.yfuse.core.model.TmdbItem

/** A new recommendation batch starts with its new lead; ordinary state updates keep the selection. */
@Composable
internal fun rememberHomeRecommendationHeroState(items: List<TmdbItem>): LivingPosterHeroState {
    val ids = items.map { "${it.mediaType}:${it.id}" }
    return key(ids) { rememberLivingPosterHeroState(ids) }
}
