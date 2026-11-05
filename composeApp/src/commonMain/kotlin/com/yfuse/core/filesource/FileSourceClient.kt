package com.yfuse.core.filesource

/**
 * Reads a 文件来源: folder listings, and the small sidecar files that travel with a video.
 *
 * Video bytes never go through here. The player reads them itself, through YCore's transports,
 * from the address and in-memory credentials a playback item carries.
 */
interface FileSourceClient {
    /** Bounded producers stop decoding/enumerating once the requested window is full. */
    suspend fun listBounded(
        source: FileSource,
        credentials: FileSourceCredentials,
        path: List<String>,
        maxEntries: Int,
    ): FileSourceListing {
        require(maxEntries in 1..MAX_FILE_SOURCE_LISTING_ENTRIES)
        val entries = list(source, credentials, path)
        return FileSourceListing(entries.take(maxEntries), entries.size > maxEntries)
    }

    fun capabilities(source: FileSource): Set<FileSourceCapability> = emptySet()

    suspend fun move(
        source: FileSource,
        credentials: FileSourceCredentials,
        from: List<String>,
        to: List<String>,
        directory: Boolean,
    ): Unit = throw UnsupportedOperationException("此来源不支持移动或重命名")

    suspend fun resolvePlayback(
        source: FileSource,
        credentials: FileSourceCredentials,
        path: List<String>,
    ): FileSourcePlaybackTarget =
        FileSourcePlaybackTarget(source.url(path, false), credentials.transportCredentials(source.kind))

    /** The entries of the folder at [path] below [FileSource.rootSegments], unsorted. */
    suspend fun list(
        source: FileSource,
        credentials: FileSourceCredentials,
        path: List<String>,
    ): List<FileSourceEntry>

    /**
     * Copies the subtitle at [path] into this device's cache, re-encoded as UTF-8, and returns a
     * local URI the player can open with no credentials at all.
     *
     * A cached copy is reused while [entry]'s size and date are unchanged, so opening the same
     * episode again does not fetch its sidecars again.
     */
    suspend fun cacheSubtitle(
        source: FileSource,
        credentials: FileSourceCredentials,
        path: List<String>,
        entry: FileSourceEntry,
    ): String
}

const val MAX_FILE_SOURCE_LISTING_ENTRIES = 10_000

data class FileSourceListing(
    val entries: List<FileSourceEntry>,
    val truncated: Boolean = false,
)

enum class FileSourceCapability { Rename, Move }

data class FileSourcePlaybackTarget(
    val url: String,
    val credentials: com.yfuse.core2.network.YTransportCredentials? = null,
    val headers: Map<String, String> = emptyMap(),
    val objectIdentity: String? = null,
    val expectedSizeBytes: Long? = null,
) {
    override fun toString(): String = "FileSourcePlaybackTarget([redacted])"
}

/** The platform's reader: OkHttp for WebDAV and jcifs-ng for SMB on Android. */
expect fun createFileSourceClient(): FileSourceClient

/** Why a request to a share failed, in the terms a person can act on. */
enum class FileSourceFailure {
    Unauthorized,
    Forbidden,
    NotFound,

    /** The address answers HTTP but not WebDAV: a web page, a login portal, the wrong path. */
    NotWebDav,
    Unreachable,
    Timeout,
    Tls,
    TooLarge,
    InvalidResponse,
    Server,
}

/** Carries a [FileSourceFailure]; the message is fixed, so it can never quote an address or path. */
class FileSourceException(
    val failure: FileSourceFailure,
    /** The HTTP status when there was one, for diagnostics. */
    val status: Int? = null,
    cause: Throwable? = null,
) : Exception("File source request failed: $failure", cause)

/** What the browser or the form says for [this] on a source of [kind]. */
fun FileSourceFailure.userMessage(kind: FileSourceKind): String =
    when (this) {
        FileSourceFailure.Unauthorized ->
            if (kind == FileSourceKind.Pan123 ||
                kind == FileSourceKind.Baidu
            ) {
                "令牌无效或授权已过期，请重新授权"
            } else {
                "用户名或密码错误"
            }
        FileSourceFailure.Forbidden -> "没有访问这个文件夹的权限"
        FileSourceFailure.NotFound ->
            if (kind == FileSourceKind.Smb) "找不到这个共享或文件夹" else "找不到这个路径"
        FileSourceFailure.NotWebDav ->
            if (kind == FileSourceKind.Alist) {
                "这个地址不是 Alist 的 WebDAV 入口，请确认已开启 WebDAV 权限"
            } else {
                "这个地址不支持 WebDAV，请检查路径"
            }
        FileSourceFailure.Unreachable ->
            if (kind == FileSourceKind.Smb) {
                "无法连接这台设备，请确认地址并已开启 SMB 共享"
            } else {
                "无法连接服务器，请检查地址和网络"
            }
        FileSourceFailure.Timeout -> "连接超时，请稍后重试"
        FileSourceFailure.Tls -> "无法验证 HTTPS 证书，请检查证书或改用 HTTP"
        FileSourceFailure.TooLarge -> "文件过大，无法作为字幕读取"
        FileSourceFailure.InvalidResponse -> "服务器返回的目录无法识别"
        FileSourceFailure.Server -> "服务器出错，请稍后重试"
    }

/** Maps an HTTP status that ended a WebDAV request. */
internal fun webDavFailureFor(status: Int): FileSourceFailure =
    when (status) {
        401, 407 -> FileSourceFailure.Unauthorized
        403 -> FileSourceFailure.Forbidden
        404, 410 -> FileSourceFailure.NotFound
        // Method Not Allowed / Not Implemented: the path is served, but not by a WebDAV handler.
        405, 501 -> FileSourceFailure.NotWebDav
        in 500..599 -> FileSourceFailure.Server
        else -> FileSourceFailure.InvalidResponse
    }
