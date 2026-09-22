package com.yfuse.core.data

/** Query parameters supported by Emby's /Users/{id}/Items search endpoint. */
data class MediaSearchFilter(
    val parentId: String? = null,
    val includeItemTypes: String = "Movie,Series",
    val productionYear: Int? = null,
    val genre: String? = null,
    val played: Boolean? = null,
    val resumable: Boolean = false,
    /** Null lets Emby's search endpoint preserve its relevance ordering. */
    val sortBy: String? = null,
    val descending: Boolean = false,
)
