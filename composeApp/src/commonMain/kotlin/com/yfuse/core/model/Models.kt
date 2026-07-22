package com.yfuse.core.model

/** An Emby server the user connects to. */
data class Server(val baseUrl: String)

/** An authenticated Emby user session. */
data class User(val id: String, val name: String, val accessToken: String)

/** A media library ("view") such as Movies or TV Shows. */
data class MediaLibrary(
    val id: String,
    val name: String,
    val collectionType: String?,
)
