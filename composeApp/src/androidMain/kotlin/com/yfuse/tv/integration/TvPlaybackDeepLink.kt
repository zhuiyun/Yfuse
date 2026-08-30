package com.yfuse.tv.integration

import com.yfuse.core.model.MediaServerKind
import com.yfuse.core.model.SavedServer
import java.io.ByteArrayOutputStream
import java.net.URI

/** Parsed URI still contains no server address, profile id or credential. */
data class EncodedTvPlaybackRoute(
    val provider: TvMediaProvider,
    val opaqueLaneId: String,
    val itemId: String,
    val positionMs: Long,
)

data class ResolvedTvPlaybackTarget(
    val provider: TvMediaProvider,
    val serverId: String,
    val profileId: String,
    val itemId: String,
    val positionMs: Long,
)

/**
 * Credential-free playback URI used by Watch Next, Preview Channels and a future Engage adapter.
 *
 * Shape: `yfuse://tv/play/v1/{provider}/{opaque server+profile lane}/{item}?p={milliseconds}`.
 * Access tokens, server URLs and raw profile ids are not accepted fields, so they cannot leak by
 * accidentally passing an authenticated playback URL to the launcher.
 */
object TvPlaybackDeepLinkCodec {
    const val SCHEME = "yfuse"
    const val HOST = "tv"
    const val PATH_PREFIX = "/play/v1/"

    fun encode(
        identity: ContinueWatchingIdentity,
        positionMs: Long,
    ): String =
        buildString {
            append("$SCHEME://$HOST$PATH_PREFIX")
            append(identity.scope.provider.uriSlug)
            append('/')
            append(identity.scope.opaqueLaneId)
            append('/')
            append(percentEncodePathSegment(identity.itemId))
            append("?p=")
            append(positionMs.coerceAtLeast(0L))
        }

    fun decode(value: String): EncodedTvPlaybackRoute? {
        if (value.length !in 1..MAX_DEEP_LINK_CHARS) return null
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        if (!uri.scheme.equals(SCHEME, ignoreCase = true)) return null
        if (!uri.host.equals(HOST, ignoreCase = true)) return null
        if (uri.userInfo != null || uri.port != -1 || uri.rawFragment != null) return null
        val segments = uri.rawPath.orEmpty().split('/').filter(String::isNotEmpty)
        if (segments.size != 5 || segments[0] != "play" || segments[1] != "v1") return null
        val provider = TvMediaProvider.entries.firstOrNull { it.uriSlug == segments[2].lowercase() } ?: return null
        val lane = segments[3]
        if (!OPAQUE_LANE.matches(lane)) return null
        val itemId = percentDecode(segments[4])?.takeIf { it.isNotBlank() && it.length <= MAX_ITEM_ID_CHARS } ?: return null
        val query = parseStrictQuery(uri.rawQuery) ?: return null
        val position = query[POSITION_PARAMETER]?.toLongOrNull()?.takeIf { it >= 0L } ?: return null
        return EncodedTvPlaybackRoute(provider, lane, itemId, position)
    }

    private fun parseStrictQuery(rawQuery: String?): Map<String, String>? {
        if (rawQuery.isNullOrBlank()) return null
        val values = linkedMapOf<String, String>()
        rawQuery.split('&').forEach { pair ->
            val separator = pair.indexOf('=')
            if (separator <= 0) return null
            val key = percentDecode(pair.substring(0, separator)) ?: return null
            val value = percentDecode(pair.substring(separator + 1)) ?: return null
            if (key != POSITION_PARAMETER || values.put(key, value) != null) return null
        }
        return values
    }

    private val TvMediaProvider.uriSlug: String
        get() = name.lowercase()

    private const val POSITION_PARAMETER = "p"
    private const val MAX_DEEP_LINK_CHARS = 2_048
    private const val MAX_ITEM_ID_CHARS = 512
    private val OPAQUE_LANE = Regex("[A-Za-z0-9_-]{22}")
}

/** Resolves the opaque lane only against the user's current, authenticated server registry. */
class TvPlaybackDeepLinkResolver(
    private val servers: () -> Collection<SavedServer>,
) {
    fun resolve(value: String): ResolvedTvPlaybackTarget? =
        TvPlaybackDeepLinkCodec.decode(value)?.let(::resolve)

    fun resolve(route: EncodedTvPlaybackRoute): ResolvedTvPlaybackTarget? {
        val candidates =
            servers().filter { server ->
                server.kind.toTvProvider() == route.provider &&
                    (setOf(server.id) + server.previousIds).any { candidateId ->
                        ContinueWatchingScope(
                            provider = route.provider,
                            serverId = candidateId,
                            profileId = server.userId,
                        ).opaqueLaneId == route.opaqueLaneId
                    }
            }
        val server = candidates.singleOrNull() ?: return null
        return ResolvedTvPlaybackTarget(
            provider = route.provider,
            serverId = server.id,
            profileId = server.userId,
            itemId = route.itemId,
            positionMs = route.positionMs,
        )
    }
}

/** Removes every unrecognised query field before artwork is handed to another process. */
internal fun sanitizeTvArtworkUri(value: String?): String? {
    val candidate = value?.takeIf { it.length in 1..MAX_ARTWORK_URI_CHARS } ?: return null
    val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
    if (uri.scheme?.lowercase() !in setOf("http", "https")) return null
    if (uri.host.isNullOrBlank() || uri.userInfo != null || uri.rawFragment != null) return null
    val safeQuery =
        uri.rawQuery
            ?.split('&')
            .orEmpty()
            .mapNotNull { pair ->
                val rawName = pair.substringBefore('=', missingDelimiterValue = pair)
                val name = percentDecode(rawName)?.lowercase() ?: return@mapNotNull null
                pair.takeIf { name in SAFE_ARTWORK_QUERY_PARAMETERS }
            }.joinToString("&")
            .takeIf(String::isNotBlank)
    return buildString {
        append(uri.scheme.lowercase())
        append("://")
        append(uri.rawAuthority)
        append(uri.rawPath.orEmpty())
        if (safeQuery != null) {
            append('?')
            append(safeQuery)
        }
    }
}

internal fun MediaServerKind.toTvProvider(): TvMediaProvider =
    when (this) {
        MediaServerKind.Emby -> TvMediaProvider.Emby
        MediaServerKind.Jellyfin -> TvMediaProvider.Jellyfin
        MediaServerKind.Plex -> TvMediaProvider.Plex
    }

private fun percentEncodePathSegment(value: String): String =
    buildString {
        value.encodeToByteArray().forEach { byte ->
            val unsigned = byte.toInt() and 0xFF
            if (
                unsigned in 'a'.code..'z'.code ||
                unsigned in 'A'.code..'Z'.code ||
                unsigned in '0'.code..'9'.code ||
                unsigned == '-'.code ||
                unsigned == '.'.code ||
                unsigned == '_'.code ||
                unsigned == '~'.code
            ) {
                append(unsigned.toChar())
            } else {
                append('%')
                append(HEX[unsigned ushr 4])
                append(HEX[unsigned and 0x0F])
            }
        }
    }

private fun percentDecode(value: String): String? {
    val output = ByteArrayOutputStream(value.length)
    var index = 0
    while (index < value.length) {
        val character = value[index]
        if (character == '%') {
            if (index + 2 >= value.length) return null
            val high = value[index + 1].digitToIntOrNull(16) ?: return null
            val low = value[index + 2].digitToIntOrNull(16) ?: return null
            output.write((high shl 4) or low)
            index += 3
        } else {
            if (character.code > 0x7F) return null
            output.write(character.code)
            index++
        }
    }
    return runCatching { output.toByteArray().decodeToString(throwOnInvalidSequence = true) }.getOrNull()
}

private const val HEX = "0123456789ABCDEF"
private const val MAX_ARTWORK_URI_CHARS = 4_096
private val SAFE_ARTWORK_QUERY_PARAMETERS =
    setOf(
        "tag",
        "maxheight",
        "maxwidth",
        "quality",
        "format",
        "width",
        "height",
        "minsize",
        "upscale",
    )
