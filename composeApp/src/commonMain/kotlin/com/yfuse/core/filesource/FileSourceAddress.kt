package com.yfuse.core.filesource

/** What 添加文件来源 collects, before any of it is checked. */
data class FileSourceDraft(
    val kind: FileSourceKind = FileSourceKind.WebDav,
    val https: Boolean = true,
    val host: String = "",
    val port: String = "",
    val path: String = "",
    val username: String = "",
    val password: String = "",
    val name: String = "",
)

/** The verdict on a [FileSourceDraft]'s address; see [resolveAddress]. */
sealed interface FileSourceAddress {
    data class Valid(
        val origin: String,
        val rootSegments: List<String>,
        /** The name typed in, or one made from the address when that was left blank. */
        val name: String,
    ) : FileSourceAddress

    /** [message] is shown under the form as it stands; it never repeats the address. */
    data class Invalid(
        val message: String,
    ) : FileSourceAddress
}

/** The port a blank 端口 stands for, as the form's placeholder shows it. */
fun FileSourceKind.defaultPort(https: Boolean): Int =
    when (this) {
        FileSourceKind.WebDav -> if (https) HTTPS_PORT else HTTP_PORT
        // Alist and OpenList listen on 5244 out of the box; behind a TLS proxy they are on 443.
        FileSourceKind.Alist -> if (https) HTTPS_PORT else ALIST_PORT
        FileSourceKind.Smb -> SMB_PORT
    }

/**
 * Checks the draft and builds the origin and root it describes.
 *
 * The host field may hold a whole pasted address (`https://nas:5006/dav/影视`); it is split into
 * the other fields first — see [withPastedAddress] — so a paste and the same thing typed field by
 * field resolve alike.
 */
fun FileSourceDraft.resolveAddress(): FileSourceAddress {
    val draft = withPastedAddress()
    // `nas.local/dav` and a UNC `\\nas\share` both carry their path in the host field.
    val typed =
        draft.host
            .trim()
            .replace('\\', '/')
            .trim('/')
    val rawHost = typed.substringBefore('/')
    if (rawHost.isEmpty()) return FileSourceAddress.Invalid("请输入地址")
    val (host, portInHost) = splitHostPort(rawHost) ?: return FileSourceAddress.Invalid("地址格式不正确")
    if (!host.isValidHost()) return FileSourceAddress.Invalid("地址格式不正确")
    val portText = draft.port.trim().ifEmpty { portInHost.orEmpty() }
    val port =
        if (portText.isEmpty()) {
            draft.kind.defaultPort(draft.https)
        } else {
            portText.toIntOrNull()?.takeIf { it in 1..MAX_PORT } ?: return FileSourceAddress.Invalid("端口需为 1–65535")
        }
    val segments =
        (typed.substringAfter('/', missingDelimiterValue = "") + "/" + draft.path)
            .split('/', '\\')
            .filter { it.isNotEmpty() && it != "." }
    if (segments.any { it == ".." }) return FileSourceAddress.Invalid("路径不能包含 ..")
    if (segments.any { segment -> segment.any { it.isISOControl() } }) {
        return FileSourceAddress.Invalid("路径中不能有控制字符")
    }
    val root =
        if (draft.kind == FileSourceKind.Alist && segments.none { it.equals(ALIST_DAV_SEGMENT, ignoreCase = true) }) {
            // Alist serves WebDAV under /dav; a path given without it is a path inside the mount.
            listOf(ALIST_DAV_SEGMENT) + segments
        } else {
            segments
        }
    val scheme =
        when {
            draft.kind == FileSourceKind.Smb -> "smb"
            draft.https -> "https"
            else -> "http"
        }
    val standardPort =
        when (scheme) {
            "https" -> HTTPS_PORT
            "http" -> HTTP_PORT
            else -> SMB_PORT
        }
    val authority = if (port == standardPort) host else "$host:$port"
    return FileSourceAddress.Valid(
        origin = "$scheme://$authority",
        rootSegments = root,
        name = sanitizedSourceName(draft.name) ?: defaultSourceName(draft.kind, host, root),
    )
}

/**
 * Splits an address pasted into 地址 into the form's fields; returns the draft unchanged when the
 * host field holds only a host.
 *
 * `smb://`, `webdav(s)://`, `dav(s)://` and `http(s)://` are recognised. Credentials pasted in
 * the address move to 用户名 and 密码 rather than staying part of an address that is shown on a
 * card. The path is percent-decoded, because that is how it is stored and shown.
 */
fun FileSourceDraft.withPastedAddress(): FileSourceDraft {
    val text = host.trim()
    val separator = text.indexOf("://")
    if (separator <= 0) return this
    val scheme = text.substring(0, separator).lowercase()
    val (kind, https) =
        when (scheme) {
            "smb" -> FileSourceKind.Smb to false
            "http", "webdav", "dav" -> (if (kind == FileSourceKind.Smb) FileSourceKind.WebDav else kind) to false
            "https", "webdavs", "davs" -> (if (kind == FileSourceKind.Smb) FileSourceKind.WebDav else kind) to true
            else -> return this
        }
    val remainder = text.substring(separator + 3).substringBefore('?').substringBefore('#')
    val authorityEnd = remainder.indexOf('/').let { if (it < 0) remainder.length else it }
    val authority = remainder.substring(0, authorityEnd)
    val userInfo = authority.substringBeforeLast('@', missingDelimiterValue = "")
    val hostPort = authority.substringAfterLast('@')
    val pastedPath =
        remainder
            .substring(authorityEnd)
            .split('/')
            .filter(String::isNotEmpty)
            .joinToString("/") { decodePathSegment(it) }
    val (pastedHost, pastedPort) = splitHostPort(hostPort) ?: (hostPort to null)
    return copy(
        kind = kind,
        https = https,
        host = pastedHost,
        port = pastedPort ?: port,
        path = pastedPath.ifEmpty { path },
        username =
            if (userInfo.isNotEmpty()) decodePathSegment(userInfo.substringBefore(':')) else username,
        password =
            if (':' in userInfo) decodePathSegment(userInfo.substringAfter(':')) else password,
    )
}

/**
 * `nas.local:5006` → (`nas.local`, `5006`); `[fe80::1]:445` → (`[fe80::1]`, `445`); a bare
 * IPv6 address is bracketed so it can carry a port. Null when the text is not a host at all.
 */
private fun splitHostPort(text: String): Pair<String, String?>? {
    if (text.startsWith("[")) {
        val close = text.indexOf(']')
        if (close < 0) return null
        val rest = text.substring(close + 1)
        return when {
            rest.isEmpty() -> text.substring(0, close + 1) to null
            rest.startsWith(":") -> text.substring(0, close + 1) to rest.substring(1)
            else -> null
        }
    }
    val colons = text.count { it == ':' }
    return when {
        colons == 0 -> text to null
        colons == 1 -> text.substringBefore(':') to text.substringAfter(':')
        // More than one colon and no brackets: an IPv6 literal with no port.
        else -> "[$text]" to null
    }
}

private fun String.isValidHost(): Boolean {
    if (isEmpty() || length > MAX_HOST_CHARS) return false
    if (startsWith("[")) {
        val inner = removePrefix("[").removeSuffix("]")
        return inner.isNotEmpty() && inner.all { it.isLetterOrDigit() || it == ':' || it == '.' || it == '%' }
    }
    return all { it.isLetterOrDigit() || it in "-._" } && !startsWith('.') && !startsWith('-')
}

private fun sanitizedSourceName(name: String): String? =
    name
        .replace('\r', ' ')
        .replace('\n', ' ')
        .trim()
        .take(MAX_SOURCE_NAME_CHARS)
        .takeIf(String::isNotEmpty)

/** The deepest folder the source opens at, or `Alist · nas.local` when the root says nothing. */
private fun defaultSourceName(
    kind: FileSourceKind,
    host: String,
    root: List<String>,
): String =
    root
        .lastOrNull()
        ?.takeUnless { it.equals(ALIST_DAV_SEGMENT, ignoreCase = true) || it.equals("webdav", ignoreCase = true) }
        ?.take(MAX_SOURCE_NAME_CHARS)
        ?: "${kind.shortLabel} · ${host.removeSurrounding("[", "]")}".take(MAX_SOURCE_NAME_CHARS)

private val FileSourceKind.shortLabel: String
    get() =
        when (this) {
            FileSourceKind.WebDav -> "WebDAV"
            FileSourceKind.Smb -> "SMB"
            FileSourceKind.Alist -> "Alist"
        }

internal const val ALIST_DAV_SEGMENT = "dav"
internal const val MAX_SOURCE_NAME_CHARS = 60
private const val MAX_HOST_CHARS = 253
private const val MAX_PORT = 65_535
private const val HTTP_PORT = 80
private const val HTTPS_PORT = 443
private const val ALIST_PORT = 5244
private const val SMB_PORT = 445
