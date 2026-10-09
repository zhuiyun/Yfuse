package com.yfuse.core.filesource

import com.yfuse.core2.network.YTransportCredentials
import kotlinx.serialization.Serializable

/** Which protocol a 文件来源 is read through. */
@Serializable
enum class FileSourceKind(
    val label: String,
) {
    WebDav("WebDAV"),
    Smb("SMB"),

    /**
     * Alist or its fork OpenList, reached through the WebDAV endpoint both serve at `/dav`.
     *
     * Kept apart from [WebDav] although it speaks the same protocol: the form fills the port and
     * the `/dav` root in for it, and a 网盘 mounted there answers file reads with a redirect to
     * the drive's own CDN, which is worth naming when a connection fails. Nothing here talks to
     * the Alist API or to any drive's API; the drives are only ever reached through that endpoint.
     */
    Alist("Alist / OpenList"),
}

/**
 * 文件来源: a share Yfuse reads files from directly, as opposed to a media server that describes
 * its own library.
 *
 * Deliberately not a [com.yfuse.core.model.SavedServer]. Everything that consumes one — health
 * probes, library caches, playback reporting, account sync, the migration package — speaks the
 * Emby API to it, and a WebDAV share answering each of those with 405 would have to be
 * special-cased in every one of them. A separate type and store means none of them can see one.
 *
 * Holds no secret. The password lives in the secure store and is read only when a connection is
 * about to be made; see [FileSourceRegistry.credentials].
 */
data class FileSource(
    /** Random and stable; the key for its password, its progress and its matches. */
    val id: String,
    val kind: FileSourceKind,
    val name: String,
    /**
     * Scheme, host and port, without a path or credentials: `https://nas.local:5006`,
     * `http://192.168.1.2:5244`, `smb://192.168.1.2`.
     */
    val origin: String,
    /**
     * Decoded path segments from [origin] to the folder browsing starts in: `["dav"]` for an
     * Alist, `["影视", "电影"]` for a WebDAV share mounted deeper. For SMB the first segment is
     * the share, and an empty list lists the machine's shares.
     */
    val rootSegments: List<String> = emptyList(),
    /** Empty for guest access. `WORKGROUP\user` names an SMB domain. */
    val username: String = "",
) {
    val usesWebDav: Boolean get() = kind != FileSourceKind.Smb

    /** `nas.local:5006` — what a card or a row says the source is, without scheme or path. */
    val hostLabel: String
        get() = origin.substringAfter("://")

    /** The address of the folder or file at [path], below [rootSegments]. */
    fun url(
        path: List<String>,
        directory: Boolean,
    ): String = fileSourceUrl(origin, rootSegments + path, directory, encode = usesWebDav)
}

/** What a connection needs beyond the [FileSource] itself. Never persisted in ordinary settings. */
class FileSourceCredentials(
    val username: String,
    val password: String,
) {
    val anonymous: Boolean get() = username.isBlank()

    /**
     * The player-side form of the same login, handed to YCore's transports in memory only.
     *
     * A `DOMAIN\user` name is split here because jcifs takes the two apart, and a WebDAV server
     * receives the name exactly as it was typed.
     */
    fun transportCredentials(kind: FileSourceKind): YTransportCredentials? {
        if (anonymous) return null
        if (kind != FileSourceKind.Smb) return YTransportCredentials.UsernamePassword(username, password)
        val (domain, user) = smbAccountParts(username)
        return YTransportCredentials.UsernamePassword(user, password, domain)
    }

    override fun toString(): String = "FileSourceCredentials([redacted])"
}

/**
 * `WORKGROUP\alice` → (`WORKGROUP`, `alice`); a plain name has no domain.
 *
 * `alice@corp.example` is left whole on purpose: jcifs and Samba both accept a UPN as the user
 * name, and splitting it would send the wrong realm to a server that expected the whole thing.
 */
internal fun smbAccountParts(username: String): Pair<String, String> {
    val trimmed = username.trim()
    val separator = trimmed.indexOf('\\')
    if (separator <= 0 || separator == trimmed.lastIndex) return "" to trimmed
    return trimmed.substring(0, separator) to trimmed.substring(separator + 1)
}

/**
 * Joins [segments] onto [origin].
 *
 * WebDAV paths are percent-encoded one segment at a time, so a `/` or `#` inside a file name can
 * never end the segment early. SMB paths are not encoded at all: jcifs reads the path of its URL
 * literally, and `%20` in one would name a file with a percent sign in it.
 */
internal fun fileSourceUrl(
    origin: String,
    segments: List<String>,
    directory: Boolean,
    encode: Boolean,
): String =
    buildString {
        append(origin.trimEnd('/'))
        append('/')
        segments.forEachIndexed { index, segment ->
            if (index > 0) append('/')
            append(if (encode) encodePathSegment(segment) else segment)
        }
        if (directory && segments.isNotEmpty()) append('/')
    }

/**
 * RFC 3986 path-segment encoding over UTF-8. Everything but the unreserved set is escaped — more
 * than strictly required, but every WebDAV server decodes it, and a sub-delimiter such as `;` or
 * `+` left bare is read as syntax by some of them.
 */
internal fun encodePathSegment(segment: String): String =
    buildString {
        segment.encodeToByteArray().forEach { byte ->
            val value = byte.toInt() and 0xFF
            val char = value.toChar()
            if (value < 0x80 && (char.isLetterOrDigit() || char in "-._~")) {
                append(char)
            } else {
                append('%')
                append(HEX_DIGITS[value shr 4])
                append(HEX_DIGITS[value and 0x0F])
            }
        }
    }

/**
 * Decodes `%XX` escapes as UTF-8. An escape that is malformed is kept as written rather than
 * failing the whole path: servers do send the odd bare `%` in a name.
 */
internal fun decodePathSegment(segment: String): String {
    if ('%' !in segment) return segment
    val bytes = ArrayList<Byte>(segment.length)
    var index = 0
    while (index < segment.length) {
        val escaped = segment[index] == '%' && index + 2 < segment.length
        val high = if (escaped) segment[index + 1].hexValue() else null
        val low = if (escaped) segment[index + 2].hexValue() else null
        if (high != null && low != null) {
            bytes += ((high shl 4) or low).toByte()
            index += 3
            continue
        }
        // A literal run up to the next escape, encoded whole so a surrogate pair stays one character.
        val next = segment.indexOf('%', index + 1).let { if (it < 0) segment.length else it }
        segment.substring(index, next).encodeToByteArray().forEach { bytes += it }
        index = next
    }
    return bytes.toByteArray().decodeToString()
}

private fun Char.hexValue(): Int? =
    when (this) {
        in '0'..'9' -> this - '0'
        in 'a'..'f' -> this - 'a' + 10
        in 'A'..'F' -> this - 'A' + 10
        else -> null
    }

private const val HEX_DIGITS = "0123456789ABCDEF"

/**
 * Every 文件来源 log line uses this category and a fixed event name. Attributes carry source ids,
 * kinds, counts and exception class names — never an address, a path or a user name.
 */
internal const val LOG_CATEGORY = "filesource"
