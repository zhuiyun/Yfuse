package com.yfuse.core.sync

import com.yfuse.core.data.WatchTogetherPreferences

/**
 * A share-able pointer to a watch-together room.
 *
 * The room code alone is not enough to make joining one tap: the guest's copy of the film
 * lives on *their* server under *their* item id, so the invite has to carry enough to
 * resolve it locally. [mediaKey] is the room's cross-server identity (`tmdb:…`, `imdb:…`,
 * or `emby:…` as a same-server fallback — see `PlayerStore.watchKey`) and [title] lets the
 * confirmation sheet name the film before that lookup has finished.
 *
 * [endpoint] is retained only to recognize links produced by older releases. Protocol v5 binds
 * Together Watch to the account service and sends an account access token, so current links never
 * emit an endpoint and a parsed non-official endpoint must be rejected before joining.
 */
data class WatchInvite(
    val roomCode: String,
    val mediaKey: String? = null,
    val title: String? = null,
    val endpoint: String? = null,
) {
    /** A legacy link-supplied relay that protocol v5 must not contact. */
    val unsupportedEndpoint: String?
        get() = endpoint?.takeUnless(WatchTogetherPreferences::isOfficialEndpoint)

    /** The deep link half of a share — paired with human-readable text by [shareText]. */
    fun toUri(): String =
        buildString {
            append(SCHEME)
            append("://")
            append(HOST)
            append('/')
            append(roomCode)
            val params =
                buildList {
                    mediaKey?.let { add("k=" + encodeComponent(it)) }
                    title?.let { add("t=" + encodeComponent(it)) }
                }
            if (params.isNotEmpty()) {
                append('?')
                append(params.joinToString("&"))
            }
        }

    /**
     * What actually gets pasted into a chat app. The link carries the one-tap path; the
     * spelled-out code is the fallback for anyone whose messenger won't linkify a custom
     * scheme, or who doesn't have the app installed yet (there's no domain to hang an
     * Android App Link off, so the scheme is all we have).
     */
    fun shareText(): String =
        buildString {
            append("用 Yfuse 一起看")
            title?.let { append("《$it》") }
            append("\n房间码 ")
            append(roomCode)
            append('\n')
            append(toUri())
        }

    companion object {
        const val SCHEME = "yfuse"
        const val HOST = "watch"
        const val CODE_LENGTH = 6

        /** Longer than any link this app writes; a scheme handler can be sent anything. */
        const val MAX_LINK_CHARS = 1_024
        const val MAX_TITLE_CHARS = 120

        /** `tmdb:1399/s2e5`, `tmdb-movie:603`, `emby:abc123`, `title:foo/s1e2` — and nothing wider. */
        private val MEDIA_KEY_SHAPE = Regex("[A-Za-z0-9_-]{1,24}:[A-Za-z0-9_.-]{1,128}(?:/s\\d{1,4}e\\d{1,5})?")

        private const val CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

        /** Normalizes user-typed input: strips separators, upper-cases, drops characters
         *  that aren't in the server's room-code alphabet (so `O`/`0` confusion fails fast
         *  and visibly rather than silently joining nothing). */
        fun normalizeCode(raw: String): String = raw.uppercase().filter { it in CODE_ALPHABET }.take(CODE_LENGTH)

        fun isCompleteCode(raw: String): Boolean = normalizeCode(raw).length == CODE_LENGTH

        /**
         * Parses a `yfuse://watch/CODE?...` link. Returns null for anything that isn't one,
         * including a well-formed link whose code isn't a plausible room code.
         *
         * Hand-rolled rather than using a URI parser because this lives in `commonMain`,
         * where `java.net.URI` isn't available.
         */
        fun parse(raw: String): WatchInvite? {
            val trimmed = raw.trim()
            // Anything on the device can send this link. Bound it the way the TV deep link is
            // bounded, before it can reach a confirmation dialog or a server lookup.
            if (trimmed.length > MAX_LINK_CHARS) return null
            val prefix = "$SCHEME://$HOST/"
            if (!trimmed.startsWith(prefix, ignoreCase = true)) return null
            val body = trimmed.removeRange(0, prefix.length)
            val query = body.substringAfter('?', "")
            val code = normalizeCode(body.substringBefore('?'))
            if (code.length != CODE_LENGTH) return null

            val params =
                query
                    .split('&')
                    .filter { it.isNotBlank() }
                    .mapNotNull { pair ->
                        val key = pair.substringBefore('=')
                        val value = pair.substringAfter('=', "")
                        if (key.isBlank()) null else key to decodeComponent(value)
                    }.toMap()

            val mediaKey = params["k"]?.takeIf { it.isNotBlank() }
            if (mediaKey != null && !MEDIA_KEY_SHAPE.matches(mediaKey)) return null
            return WatchInvite(
                roomCode = code,
                mediaKey = mediaKey,
                title = params["t"]?.trim()?.takeIf { it.isNotBlank() }?.take(MAX_TITLE_CHARS),
                endpoint = params["e"]?.takeIf { it.isNotBlank() }?.take(MAX_LINK_CHARS),
            )
        }

        /**
         * Finds an invite inside arbitrary shared text — the whole [shareText] block pasted
         * from a chat app, or just a bare 6-character code someone typed out. Used by the
         * paste-to-join entry so users don't have to surgically extract the link.
         */
        fun parseFromText(raw: String): WatchInvite? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null

            val linkStart = trimmed.indexOf("$SCHEME://$HOST/", ignoreCase = true)
            if (linkStart >= 0) {
                val candidate = trimmed.substring(linkStart).takeWhile { !it.isWhitespace() }
                parse(candidate)?.let { return it }
            }

            // A bare code, possibly surrounded by other words ("房间码 ABC123").
            trimmed
                .split(' ', '\n', '\t', '\r', '：', ':')
                .map { normalizeCode(it) }
                .firstOrNull { it.length == CODE_LENGTH }
                ?.let { return WatchInvite(roomCode = it) }

            return null
        }
    }
}

/**
 * Minimal percent-encoding for the invite's query values. Only `commonMain`-safe APIs are
 * available here, so this encodes conservatively: anything outside an unreserved ASCII set
 * (including all non-ASCII, which titles are full of) becomes UTF-8 percent-escapes.
 */
private fun encodeComponent(value: String): String {
    val unreserved = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.~"
    return buildString {
        value.encodeToByteArray().forEach { byte ->
            val char = byte.toInt().toChar()
            if (byte >= 0 && char in unreserved) {
                append(char)
            } else {
                append('%')
                append(HEX_DIGITS[(byte.toInt() shr 4) and 0x0F])
                append(HEX_DIGITS[byte.toInt() and 0x0F])
            }
        }
    }
}

private fun decodeComponent(value: String): String {
    if ('%' !in value && '+' !in value) return value
    val bytes = ArrayList<Byte>(value.length)
    var index = 0
    while (index < value.length) {
        when (val char = value[index]) {
            '%' -> {
                val hex = value.drop(index + 1).take(2)
                val byte = hex.toIntOrNull(16)
                if (hex.length == 2 && byte != null) {
                    bytes.add(byte.toByte())
                    index += 3
                } else {
                    // Malformed escape — keep the literal character rather than dropping
                    // data, so a mangled link still shows something recognizable.
                    bytes.add(char.code.toByte())
                    index++
                }
            }
            '+' -> {
                bytes.add(' '.code.toByte())
                index++
            }
            else -> {
                bytes.add(char.code.toByte())
                index++
            }
        }
    }
    return bytes.toByteArray().decodeToString()
}

private const val HEX_DIGITS = "0123456789ABCDEF"

/** External providers a cross-server key can be built from, most preferred first. */
private val WATCH_PROVIDERS = listOf("Tmdb", "Tvdb", "Imdb")

/**
 * Every `<provider>:<value>` this metadata can produce, in preference order.
 *
 * A library holds whichever ids its scrape happened to fill in, and two libraries holding
 * the same title routinely hold different subsets. One of these is the name a device
 * *publishes* ([watchKey]); all of them are names it *answers to* ([watchMatchKeys]).
 */
fun Map<String, String>.watchKeys(): List<String> =
    WATCH_PROVIDERS.mapNotNull { provider ->
        entries
            .firstOrNull { it.key.equals(provider, ignoreCase = true) }
            ?.value
            ?.takeIf { it.isNotBlank() }
            ?.let { "${provider.lowercase()}:$it" }
    }

/**
 * The room's cross-server identity for a title.
 *
 * Prefers an external provider id so two people whose Emby servers hold different files can
 * still be recognised as watching the same thing; falls back to `emby:<id>`, which only
 * matches on the server it came from.
 */
fun Map<String, String>.watchKey(fallbackId: String): String = watchKeys().firstOrNull() ?: "emby:$fallbackId"

/** Separates a series key from the episode coordinate within it: `tmdb:1399/s2e5`. */
const val EPISODE_KEY_SEPARATOR = '/'

/**
 * Cross-server identity for one *episode*.
 *
 * Prefers the show plus a coordinate — `tmdb:1399/s2e5` — over the episode's own provider
 * id, which is the fallback.
 *
 * It used to be the other way round, on the grounds that the episode's own id is the more
 * precise of the two. Precision is not what this key is for: it has to be *the same string
 * on both devices*, and the episode's own id is the part least likely to be. Not because
 * episodes lack ids — most carry at least a `Tvdb`, often an `Imdb` — but because *which*
 * one a library holds is a property of how it was scraped, not of the episode. One side
 * ends up naming an episode `tvdb:7654321`, the other `tmdb:99`, and neither is wrong. The
 * matcher compares strings, so that is a room where nothing the host does reaches anyone:
 * pause, seek and entry changes all sit behind that one comparison.
 *
 * A coordinate depends on the show's id — one lookup further up, where libraries agree far
 * more often — plus two integers no scrape disagrees about. It is not immune either: two
 * libraries can hold different provider ids for the *show*, which is what
 * [watchMatchKeys] and the matcher's coordinate fallback are for.
 *
 * The coordinate is written even when the show has no external id at all, leaving a
 * server-local `emby:<id>/s2e5`. Half of that key is useless to anyone else and it will
 * never match on identity — but it is *readable*: the other side can still see which
 * episode the room is on, and match it inside a queue that is already this show. Two
 * people who each opened the same series by hand, on libraries that share no metadata
 * whatsoever, sync on that alone. Publishing the episode's own id instead would say
 * nothing either side could act on.
 */
fun episodeWatchKey(
    ownProviderIds: Map<String, String>,
    seriesProviderIds: Map<String, String>,
    seasonNumber: Int?,
    episodeNumber: Int?,
    fallbackId: String,
): String {
    // Nothing that numbers episodes, so there is no coordinate to write — a film, or an
    // entry whose library never filled the number in.
    if (episodeNumber == null) return ownProviderIds.watchKey(fallbackId)
    // Specials sit in season 0 on every server that has them, so a missing season number
    // is written out rather than left off — an absent coordinate would match anything.
    val series = seriesProviderIds.watchKey(fallbackId)
    return "$series$EPISODE_KEY_SEPARATOR" + "s${seasonNumber ?: 0}e$episodeNumber"
}

/**
 * Every name one entry answers to when a room says what it is playing.
 *
 * A room publishes exactly one key, chosen from the *publisher's* metadata. Comparing that
 * to one key chosen from the *listener's* metadata means both sides have to have picked the
 * same provider, and two libraries scraped at different times do not: the host names a film
 * `imdb:tt0133093` because that is all it holds, the listener names it `tmdb:603` because
 * it holds both and prefers Tmdb, and one film has two names that never meet. Listening on
 * every name this library can justify costs nothing and removes that whole class of miss.
 *
 * Ordered canonical-first — the show-and-coordinate form, then the entry's own ids, then
 * the server-local one — so the list reads as "what this is", not "what it might be".
 */
fun watchMatchKeys(
    ownProviderIds: Map<String, String>,
    seriesProviderIds: Map<String, String> = emptyMap(),
    seasonNumber: Int? = null,
    episodeNumber: Int? = null,
    fallbackId: String,
): List<String> =
    buildList {
        if (episodeNumber != null) {
            val coordinate = "$EPISODE_KEY_SEPARATOR" + "s${seasonNumber ?: 0}e$episodeNumber"
            seriesProviderIds.watchKeys().forEach { add(it + coordinate) }
        }
        addAll(ownProviderIds.watchKeys())
        add("emby:$fallbackId")
    }.distinct()

/** The `<provider>:<value>` and `season to episode` halves of an [episodeWatchKey]. */
data class EpisodeCoordinate(
    val seriesKey: String,
    val seasonNumber: Int,
    val episodeNumber: Int,
)

/** Reads back what [episodeWatchKey] wrote, or null for a plain title key. */
fun parseEpisodeWatchKey(mediaKey: String): EpisodeCoordinate? {
    val seriesKey = mediaKey.substringBefore(EPISODE_KEY_SEPARATOR, "")
    val coordinate = mediaKey.substringAfter(EPISODE_KEY_SEPARATOR, "")
    if (seriesKey.isEmpty() || !coordinate.startsWith("s")) return null
    val season = coordinate.drop(1).substringBefore('e').toIntOrNull() ?: return null
    val episode = coordinate.substringAfter('e', "").toIntOrNull() ?: return null
    return EpisodeCoordinate(seriesKey, season, episode)
}
