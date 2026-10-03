package com.yfuse.core.model

/**
 * 预告片 — one trailer for a title.
 *
 * Two kinds, because they go to different places: a file the library holds plays in the app's own
 * player, and a link a server scraped (YouTube and the like) opens in whatever app the system has
 * for it. Nothing here extracts a stream from a video site.
 */
sealed interface MediaTrailer {
    val title: String

    /**
     * An Emby/Jellyfin local trailer or a Plex trailer extra: a file on [serverId] with its own
     * [itemId], never the title it belongs to — so playing it can never be mistaken for watching
     * that title.
     */
    data class Local(
        val serverId: String,
        val itemId: String,
        override val title: String,
        /** Authenticated address of the file itself; carries the server's token. */
        val streamUrl: String,
        val durationMs: Long? = null,
    ) : MediaTrailer {
        // The generated form would print the token inside [streamUrl] into any log it reached.
        override fun toString(): String = "Local(serverId=$serverId, itemId=$itemId, title=$title)"
    }

    /** A trailer page on a video site; [site] names it for the card (YouTube, 哔哩哔哩, a host). */
    data class Remote(
        override val title: String,
        val url: String,
        val site: String,
    ) : MediaTrailer
}

/** 主题曲 — theme music the server holds for a title, or for the series an episode is in. */
data class ThemeSong(
    val serverId: String,
    val itemId: String,
    val title: String,
    /** Authenticated address of the audio file; carries the server's token. */
    val streamUrl: String,
) {
    override fun toString(): String = "ThemeSong(serverId=$serverId, itemId=$itemId, title=$title)"
}

/**
 * A person as their server knows them, for the header of 演员页.
 *
 * Dates are the `YYYY-MM-DD` the server stored, or null; nothing here is guessed. Emby and Jellyfin
 * keep a birthplace in the field they call ProductionLocations, and the birth and death dates in
 * PremiereDate and EndDate — the same fields a film uses for its release.
 */
data class PersonProfile(
    val id: String,
    val name: String,
    val overview: String? = null,
    val birthDate: String? = null,
    val deathDate: String? = null,
    val birthPlace: String? = null,
    val primaryImageTag: String? = null,
    /** External ids as the server keys them (`Tmdb`, `Imdb`); matched without regard to case. */
    val providerIds: Map<String, String> = emptyMap(),
) {
    /** The TMDB person this server already linked, if it did. */
    val tmdbId: Int?
        get() =
            providerIds.entries
                .firstOrNull { it.key.equals("Tmdb", ignoreCase = true) }
                ?.value
                ?.trim()
                ?.toIntOrNull()
                ?.takeIf { it > 0 }
}
