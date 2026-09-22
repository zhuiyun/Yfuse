package com.yfuse.core.model

/** Product capabilities that differ between Emby/Jellyfin and Plex server APIs. */
data class MediaServerCapabilities(
    val favorites: Boolean,
    val scheduledTasks: Boolean,
    val itemAnalysis: Boolean,
    val subtitleStore: Boolean,
    val metadataRefresh: Boolean = true,
)

fun MediaServerKind.capabilities(): MediaServerCapabilities =
    when (this) {
        MediaServerKind.Emby,
        MediaServerKind.Jellyfin,
        ->
            MediaServerCapabilities(
                favorites = true,
                scheduledTasks = true,
                itemAnalysis = false,
                subtitleStore = true,
            )
        MediaServerKind.Plex ->
            MediaServerCapabilities(
                favorites = false,
                scheduledTasks = false,
                itemAnalysis = true,
                subtitleStore = false,
            )
    }
