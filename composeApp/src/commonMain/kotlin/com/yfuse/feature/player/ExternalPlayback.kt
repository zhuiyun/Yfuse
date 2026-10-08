package com.yfuse.feature.player

import com.yfuse.core.model.PlaybackMethod
import com.yfuse.core.util.takeGraphemes
import com.yfuse.core.util.withoutControlCharacters
import io.ktor.http.decodeURLPart
import java.util.UUID

/** A link is an address, not a document: anything longer is not one somebody meant to paste. */
internal const val MAX_EXTERNAL_STREAM_URL_CHARS = 4_096

/** What the player shows when an outside video brings no usable name of its own. */
internal const val EXTERNAL_PLAYBACK_FALLBACK_TITLE = "外部视频"

private const val EXTERNAL_PLAYBACK_ID_PREFIX = "external-"
private const val MAX_EXTERNAL_TITLE_GRAPHEMES = 120

/**
 * Manifest names a stream server gives every stream (`…/index.m3u8`); as a title they say nothing,
 * so the host stands in for them.
 */
private val GenericStreamFileNames =
    setOf("index", "playlist", "master", "manifest", "chunklist", "prog_index", "stream", "video")

/** The verdict on a typed or shared web address; see [parseExternalStreamUrl]. */
internal sealed interface ExternalStreamUrl {
    /** [url] is trimmed and its scheme lower-cased; everything else is exactly what was given. */
    data class Accepted(
        val url: String,
    ) : ExternalStreamUrl

    /** [reason] is shown to the viewer as it stands. It never repeats the address. */
    data class Rejected(
        val reason: String,
    ) : ExternalStreamUrl
}

/**
 * Checks an address the viewer wants to play that did not come from a library.
 *
 * Only `http://` and `https://` with a host are accepted. There is no host allow-list: a phone's
 * own proxy on `127.0.0.1` and a NAS on the LAN are exactly what people paste. Whitespace, control
 * and invisible formatting characters are refused rather than repaired, because an address that
 * needs repairing is not the one that was copied.
 */
internal fun parseExternalStreamUrl(input: String): ExternalStreamUrl {
    val candidate = input.trim()
    if (candidate.isEmpty()) return ExternalStreamUrl.Rejected("请输入视频链接")
    if (candidate.length > MAX_EXTERNAL_STREAM_URL_CHARS) {
        return ExternalStreamUrl.Rejected("链接过长，最多 $MAX_EXTERNAL_STREAM_URL_CHARS 个字符")
    }
    if (candidate.any { it.isWhitespace() || it.isISOControl() || it.category == CharCategory.FORMAT }) {
        return ExternalStreamUrl.Rejected("链接中不能有空格、换行或不可见字符")
    }
    val separator = candidate.indexOf("://")
    val scheme = if (separator > 0) candidate.substring(0, separator).lowercase() else ""
    if (scheme != "http" && scheme != "https") {
        return ExternalStreamUrl.Rejected("只支持 http:// 或 https:// 开头的链接")
    }
    val remainder = candidate.substring(separator + 3)
    val authorityEnd = remainder.indexOfFirst { it == '/' || it == '?' || it == '#' }
    val authority = if (authorityEnd < 0) remainder else remainder.substring(0, authorityEnd)
    // Credentials the viewer typed into the address stay theirs to send; only the host is checked.
    val hostAndPort = authority.substringAfterLast('@')
    val host: String
    val port: String?
    if (hostAndPort.startsWith("[")) {
        val close = hostAndPort.indexOf(']')
        if (close < 0) return ExternalStreamUrl.Rejected("链接的主机地址无效")
        host = hostAndPort.substring(1, close)
        val afterHost = hostAndPort.substring(close + 1)
        port =
            when {
                afterHost.isEmpty() -> null
                afterHost.startsWith(":") -> afterHost.substring(1)
                else -> return ExternalStreamUrl.Rejected("链接的主机地址无效")
            }
    } else {
        host = hostAndPort.substringBefore(':')
        port = if (':' in hostAndPort) hostAndPort.substringAfter(':') else null
    }
    if (host.isEmpty()) return ExternalStreamUrl.Rejected("链接缺少主机地址")
    if (port != null && !port.isValidPort()) return ExternalStreamUrl.Rejected("链接的端口无效")
    return ExternalStreamUrl.Accepted(scheme + candidate.substring(separator))
}

private fun String.isValidPort(): Boolean = length in 1..5 && all { it in '0'..'9' } && toInt() in 1..65_535

/**
 * Whether an http(s) [url] points at this device or the local network: loopback, private and
 * link-local addresses, and names only a LAN resolves (`nas`, `*.local`, `*.lan`, `*.home.arpa`).
 *
 * Typed by the viewer, such an address is what they meant. Handed over by another app it is a
 * request to make Yfuse fetch from inside the network on that app's behalf - a router page, another
 * app's loopback server - so the viewer is asked first.
 */
internal fun externalStreamTargetsLocalNetwork(url: String): Boolean {
    val host = externalStreamHost(url)?.lowercase()?.trimEnd('.') ?: return false
    if (host.isEmpty()) return false
    if (host == "localhost" || host.endsWith(".localhost")) return true
    host.toIpv4Octets()?.let { return it.isLocalIpv4() }
    if (':' in host) return host.isLocalIpv6()
    return '.' !in host || LOCAL_NAME_SUFFIXES.any { host.endsWith(it) }
}

private val LOCAL_NAME_SUFFIXES = listOf(".local", ".lan", ".home", ".internal", ".intranet", ".home.arpa")

/** The host of an http(s) address, without brackets, user info or port; null when there is none. */
private fun externalStreamHost(url: String): String? {
    val separator = url.indexOf("://")
    if (separator <= 0) return null
    val remainder = url.substring(separator + 3)
    val authorityEnd = remainder.indexOfFirst { it == '/' || it == '?' || it == '#' }
    val authority = if (authorityEnd < 0) remainder else remainder.substring(0, authorityEnd)
    val hostAndPort = authority.substringAfterLast('@')
    return if (hostAndPort.startsWith("[")) {
        hostAndPort.substring(1).substringBefore(']')
    } else {
        hostAndPort.substringBefore(':')
    }
}

private fun String.toIpv4Octets(): List<Int>? {
    val parts = split('.')
    if (parts.size != 4) return null
    return parts.map { part -> part.toIntOrNull()?.takeIf { part.isNotEmpty() && it in 0..255 } ?: return null }
}

private fun List<Int>.isLocalIpv4(): Boolean {
    val (a, b) = this
    return a == 0 ||
        a == 10 ||
        a == 127 ||
        (a == 100 && b in 64..127) ||
        (a == 169 && b == 254) ||
        (a == 172 && b in 16..31) ||
        (a == 192 && b == 168)
}

private fun String.isLocalIpv6(): Boolean {
    val address = substringBefore('%')
    if (address == "::" || address == "::1") return true
    // ::ffff:192.168.1.2 is that IPv4 address.
    address
        .substringAfterLast(':')
        .toIpv4Octets()
        ?.let { if (address.startsWith("::ffff:")) return it.isLocalIpv4() }
    val first = address.substringBefore(':').toIntOrNull(16) ?: return false
    // fc00::/7 unique local, fe80::/10 link local.
    return (first and 0xFE00) == 0xFC00 || (first and 0xFFC0) == 0xFE80
}

/**
 * Whether a media address carries a sign-in credential: an Emby/Jellyfin `api_key`, a Plex token,
 * an access token, or a user name and password in the address itself. Handing it to another app
 * hands over the account with it.
 */
internal fun mediaUrlCarriesCredential(url: String): Boolean {
    val separator = url.indexOf("://")
    if (separator > 0) {
        val remainder = url.substring(separator + 3)
        val authorityEnd = remainder.indexOfFirst { it == '/' || it == '?' || it == '#' }
        val authority = if (authorityEnd < 0) remainder else remainder.substring(0, authorityEnd)
        if ('@' in authority) return true
    }
    val query = url.substringAfter('?', missingDelimiterValue = "").substringBefore('#')
    if (query.isEmpty()) return false
    return query.split('&').any { parameter ->
        val name = parameter.substringBefore('=').lowercase()
        name in CREDENTIAL_QUERY_NAMES && parameter.substringAfter('=', missingDelimiterValue = "").isNotEmpty()
    }
}

private val CREDENTIAL_QUERY_NAMES =
    setOf(
        "api_key",
        "apikey",
        "x-emby-token",
        "x-mediabrowser-token",
        "x-plex-token",
        "access_token",
        "accesstoken",
        "token",
        "auth",
        "authorization",
    )

/**
 * The first `http(s)://` address inside shared text, or null.
 *
 * Share sheets rarely send a bare link: a title comes first, or 复制打开 follows without a space.
 * The address therefore ends at whitespace, at the first non-ASCII character or at a character no
 * address may contain, and loses a sentence's closing punctuation.
 */
internal fun firstSharedWebLink(text: String): String? {
    val scanned = text.take(MAX_SHARED_TEXT_CHARS)
    val start = SharedLinkStart.find(scanned)?.range?.first ?: return null
    var end = start
    while (end < scanned.length && scanned[end].isSharedLinkCharacter()) end++
    return scanned
        .substring(start, end)
        .trimEnd('.', ',', ';', ':', '!', '?')
        .takeIf(String::isNotEmpty)
}

private const val MAX_SHARED_TEXT_CHARS = 16 * 1_024
private val SharedLinkStart = Regex("(?i)https?://")

private fun Char.isSharedLinkCharacter(): Boolean = code in 0x21..0x7E && this !in "\"<>\\^`{|}"

/**
 * A queue entry for an address that belongs to no library: a link typed into 打开链接, or a video
 * another app hands over through 打开方式.
 *
 * It carries no server identity — no server id, no play session, no token — so nothing that builds
 * library requests can attach an account to it. The stream is fetched with the address alone;
 * playback headers only ever come from credentials already inside the address and go back to its
 * own origin (see `embyPlaybackHeaders`). The id is random, so no record keyed by it outlives the
 * visit, and its prefix is what [isExternalPlayback] recognises.
 *
 * Public because the television's Cast receiver builds its PlayDirect entries with it too: an
 * address a sender casts is no more this device's library than a pasted one.
 */
fun externalPlaybackItem(
    url: String,
    title: String,
): PlayerMediaItem =
    PlayerMediaItem(
        id = EXTERNAL_PLAYBACK_ID_PREFIX + UUID.randomUUID(),
        url = url,
        transcodeUrl = "",
        fallbackTranscodeUrl = "",
        title = title,
        playMethod = PlaybackMethod.DirectPlay,
        serverTranscodeSupported = false,
    )

/**
 * True for an entry built by [externalPlaybackItem]. Legacy server-less web entries are still
 * reported to the default server; an outside address must never be, or the account there would
 * receive a stranger's playback under a made-up item id.
 */
internal val PlayerMediaItem.isExternalPlayback: Boolean
    get() = serverId == null && id.startsWith(EXTERNAL_PLAYBACK_ID_PREFIX)

/**
 * A 预告片 file from a library, queued as an outside entry.
 *
 * Its address already carries what the server needs to hand the file over, and nothing else about
 * the entry names that server: so, like any [externalPlaybackItem], it reports playback to no
 * server and scrobbles nothing to Trakt, and the title it belongs to keeps its own progress. Its id
 * says it is a trailer as well, which keeps it out of 观看历史 — see [isTrailerPlayback].
 */
fun trailerPlaybackItem(
    url: String,
    title: String,
): PlayerMediaItem =
    externalPlaybackItem(url = url, title = title)
        .copy(id = TRAILER_PLAYBACK_ID_PREFIX + UUID.randomUUID())

/** True for an entry built by [trailerPlaybackItem]: an outside entry that is a title's trailer. */
internal val PlayerMediaItem.isTrailerPlayback: Boolean
    get() = isExternalPlayback && id.startsWith(TRAILER_PLAYBACK_ID_PREFIX)

private const val TRAILER_PLAYBACK_ID_PREFIX = EXTERNAL_PLAYBACK_ID_PREFIX + "trailer-"

/**
 * The first usable name among [candidates], in the order given; the fallback when none is.
 * Control characters are dropped and the length is bounded: every candidate came from outside.
 */
internal fun externalPlaybackTitle(vararg candidates: String?): String =
    candidates
        .asSequence()
        .mapNotNull { candidate ->
            candidate
                ?.withoutControlCharacters()
                ?.trim()
                ?.takeIf(String::isNotEmpty)
        }.firstOrNull()
        ?.takeGraphemes(MAX_EXTERNAL_TITLE_GRAPHEMES)
        ?: EXTERNAL_PLAYBACK_FALLBACK_TITLE

/**
 * `电影.2024.mkv` reads `电影.2024`: only a short ASCII extension with a letter in it is taken off,
 * so neither `Movie.2024` nor a name that merely contains a dot loses its end.
 */
internal fun externalFileTitle(fileName: String?): String? {
    val name = fileName?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val dot = name.lastIndexOf('.')
    if (dot <= 0) return name
    val extension = name.substring(dot + 1)
    val isExtension =
        extension.length in 1..5 &&
            extension.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' } &&
            extension.any { it !in '0'..'9' }
    return if (isExtension) name.substring(0, dot) else name
}

/**
 * A name for an accepted web address: its last path segment as a file name, or the host when the
 * path has none or only says `index.m3u8`.
 */
internal fun externalStreamTitle(url: String): String {
    val remainder = url.substringAfter("://", missingDelimiterValue = "")
    val authorityEnd = remainder.indexOfFirst { it == '/' || it == '?' || it == '#' }
    val authority = if (authorityEnd < 0) remainder else remainder.substring(0, authorityEnd)
    val hostAndPort = authority.substringAfterLast('@')
    val host =
        if (hostAndPort.startsWith("[")) {
            hostAndPort.substring(1).substringBefore(']')
        } else {
            hostAndPort.substringBefore(':')
        }
    val segment =
        remainder
            .substring(authority.length)
            .substringBefore('?')
            .substringBefore('#')
            .trimEnd('/')
            .substringAfterLast('/')
    val decoded = runCatching { segment.decodeURLPart() }.getOrDefault(segment)
    val fileTitle = externalFileTitle(decoded)?.takeUnless { it.lowercase() in GenericStreamFileNames }
    return externalPlaybackTitle(fileTitle, host)
}
