package com.yfuse.core.data

import com.yfuse.core.model.TmdbHome

/** Failed feed groups stay distinguishable from successfully refreshed, empty shelves. */
data class TmdbHomeRefresh(
    val content: TmdbHome,
    val incompleteRows: Set<String> = emptySet(),
    val failure: TmdbRecommendationFailure? = null,
)

enum class TmdbRecommendationFailure {
    AUTHORIZATION,
    ACCESS_DENIED,
    RATE_LIMITED,
    TIMEOUT,
    NETWORK,
    INVALID_RESPONSE,
    SERVICE,
    EMPTY,
}

/** Carries a safe category without retaining request URLs or remote response bodies. */
class TmdbRecommendationException(
    val failure: TmdbRecommendationFailure,
) : Exception(failure.name)
