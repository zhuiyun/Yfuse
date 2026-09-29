package com.yfuse.core.filesource

import com.yfuse.core.logging.AppLog
import com.yfuse.core2.android.smbProperties
import jcifs.CIFSContext
import jcifs.SmbConstants
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtStatus
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbAuthException
import jcifs.smb.SmbException
import jcifs.smb.SmbFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Properties
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * WebDAV (and so Alist/OpenList) through OkHttp, SMB through jcifs-ng — the same two libraries
 * the player's transports use, so a share that lists here is one YCore can open.
 *
 * WebDAV requests follow redirects: OkHttp keeps PROPFIND and its body across a 307/308, and drops
 * the Authorization header whenever a redirect leaves the host, which is exactly what an Alist
 * serving a 网盘 through `302` needs. Nothing here logs an address, a path or a name.
 */
internal class AndroidFileSourceClient(
    private val http: OkHttpClient,
    private val subtitles: FileSourceSubtitleCache,
    private val smb: SmbShareReader = JcifsShareReader(),
) : FileSourceClient {
    override suspend fun list(
        source: FileSource,
        credentials: FileSourceCredentials,
        path: List<String>,
    ): List<FileSourceEntry> =
        if (source.usesWebDav) {
            listWebDav(source, credentials, path)
        } else {
            smb.list(
                source.url(path, directory = true),
                credentials,
                sharesOnly =
                    source.rootSegments.isEmpty() && path.isEmpty(),
            )
        }

    override suspend fun cacheSubtitle(
        source: FileSource,
        credentials: FileSourceCredentials,
        path: List<String>,
        entry: FileSourceEntry,
    ): String {
        val key = subtitleCacheKey(source.id, path, entry)
        subtitles.cached(key, entry.extension)?.let { return it }
        val url = source.url(path, directory = false)
        val bytes =
            if (source.usesWebDav) {
                readWebDav(url, credentials, MAX_SUBTITLE_BYTES)
            } else {
                smb.read(url, credentials, MAX_SUBTITLE_BYTES)
            }
        return subtitles.store(key, entry.extension, utf8SubtitleBytes(bytes, entry.name))
    }

    private suspend fun listWebDav(
        source: FileSource,
        credentials: FileSourceCredentials,
        path: List<String>,
    ): List<FileSourceEntry> {
        val request =
            Request
                .Builder()
                .url(source.url(path, directory = true))
                .method("PROPFIND", PROPFIND_BODY.toRequestBody(XML_MEDIA_TYPE))
                .header("Depth", "1")
                .header("Accept", "application/xml, text/xml;q=0.9, */*;q=0.1")
                .withBasicAuth(credentials)
                .build()
        val body =
            http.newCall(request).await().use { response ->
                if (response.code != 207 && response.code != 200) {
                    throw FileSourceException(webDavFailureFor(response.code), status = response.code)
                }
                runInterruptible(Dispatchers.IO) { response.readBounded(MAX_LISTING_BYTES) }.decodeToString()
            }
        val resources =
            try {
                parseWebDavMultistatus(body)
            } catch (error: WebDavParseException) {
                // A 200 page that is not a multistatus is a website or a login portal, not a share.
                throw FileSourceException(FileSourceFailure.NotWebDav, cause = error)
            }
        return webDavChildren(source.rootSegments + path, resources)
    }

    private suspend fun readWebDav(
        url: String,
        credentials: FileSourceCredentials,
        maxBytes: Int,
    ): ByteArray {
        val request =
            Request
                .Builder()
                .url(url)
                .get()
                .withBasicAuth(credentials)
                .build()
        return http.newCall(request).await().use { response ->
            if (!response.isSuccessful) {
                throw FileSourceException(
                    webDavFailureFor(response.code),
                    status = response.code,
                )
            }
            runInterruptible(Dispatchers.IO) { response.readBounded(maxBytes) }
        }
    }

    private fun Request.Builder.withBasicAuth(credentials: FileSourceCredentials): Request.Builder =
        if (credentials.anonymous) {
            this
        } else {
            // UTF-8 per RFC 7617: a Chinese password in ISO-8859-1 arrives as question marks.
            header("Authorization", Credentials.basic(credentials.username, credentials.password, Charsets.UTF_8))
        }

    private companion object {
        val XML_MEDIA_TYPE = "application/xml; charset=utf-8".toMediaType()
        const val PROPFIND_BODY =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                "<D:propfind xmlns:D=\"DAV:\"><D:prop>" +
                "<D:resourcetype/><D:getcontentlength/><D:getlastmodified/>" +
                "</D:prop></D:propfind>"
    }
}

/** The jcifs half, behind an interface so the WebDAV half's tests need no SMB server. */
internal interface SmbShareReader {
    suspend fun list(
        url: String,
        credentials: FileSourceCredentials,
        sharesOnly: Boolean,
    ): List<FileSourceEntry>

    suspend fun read(
        url: String,
        credentials: FileSourceCredentials,
        maxBytes: Int,
    ): ByteArray
}

/**
 * SMB2/3 only, with the same client properties as `AndroidSmbMediaTransport` — a share that
 * refuses SMB1 here is refused the same way at playback, not only there.
 */
internal class JcifsShareReader(
    private val properties: () -> Properties = ::smbProperties,
) : SmbShareReader {
    override suspend fun list(
        url: String,
        credentials: FileSourceCredentials,
        sharesOnly: Boolean,
    ): List<FileSourceEntry> =
        runInterruptible(Dispatchers.IO) {
            withSmbContext(credentials) { context ->
                SmbFile(url, context).use { folder ->
                    folder.listFiles().mapNotNull { child -> child.use { it.toEntry(sharesOnly) } }
                }
            }
        }

    override suspend fun read(
        url: String,
        credentials: FileSourceCredentials,
        maxBytes: Int,
    ): ByteArray =
        runInterruptible(Dispatchers.IO) {
            withSmbContext(credentials) { context ->
                SmbFile(url, context).use { file ->
                    if (file.length() > maxBytes) throw FileSourceException(FileSourceFailure.TooLarge)
                    file.openInputStream().use { it.readBounded(maxBytes) }
                }
            }
        }

    private fun <T> withSmbContext(
        credentials: FileSourceCredentials,
        block: (CIFSContext) -> T,
    ): T {
        val base = BaseContext(PropertyConfiguration(properties()))
        val context =
            if (credentials.anonymous) {
                base
            } else {
                val (domain, user) = smbAccountParts(credentials.username)
                base.withCredentials(NtlmPasswordAuthenticator(domain, user, credentials.password))
            }
        return try {
            block(context)
        } catch (error: FileSourceException) {
            throw error
        } catch (error: Exception) {
            throw FileSourceException(smbFailureFor(error), cause = error)
        } finally {
            runCatching { base.close() }
        }
    }

    /**
     * jcifs names a folder with a trailing slash, from the listing it already has, so this costs
     * no request per child. On a machine's share list the printers and pipes are left out, as are
     * the hidden `$` shares; see [isHiddenFileSourceName].
     */
    private fun SmbFile.toEntry(sharesOnly: Boolean): FileSourceEntry? {
        if (sharesOnly) {
            val type = runCatching { type }.getOrDefault(SmbConstants.TYPE_SHARE)
            if (type == SmbConstants.TYPE_PRINTER ||
                type == SmbConstants.TYPE_NAMED_PIPE ||
                type == SmbConstants.TYPE_COMM
            ) {
                return null
            }
        }
        val directory = name.endsWith('/')
        val clean = name.trimEnd('/')
        if (clean.isEmpty()) return null
        return FileSourceEntry(
            name = clean,
            directory = directory,
            sizeBytes = if (directory) null else runCatching { length() }.getOrNull(),
            modifiedEpochMs = runCatching { lastModified() }.getOrNull()?.takeIf { it > 0L },
        )
    }
}

/** What an SMB failure means, from the first cause that says anything. */
internal fun smbFailureFor(error: Throwable): FileSourceFailure {
    generateSequence(error) { it.cause }.take(MAX_CAUSE_DEPTH).forEach { cause ->
        when (cause) {
            is FileSourceException -> return cause.failure
            is SmbAuthException -> return FileSourceFailure.Unauthorized
            is UnknownHostException, is ConnectException, is NoRouteToHostException ->
                return FileSourceFailure.Unreachable
            is SocketTimeoutException -> return FileSourceFailure.Timeout
            is SmbException ->
                when (cause.ntStatus) {
                    NtStatus.NT_STATUS_LOGON_FAILURE,
                    NtStatus.NT_STATUS_WRONG_PASSWORD,
                    NtStatus.NT_STATUS_ACCOUNT_DISABLED,
                    NtStatus.NT_STATUS_ACCOUNT_LOCKED_OUT,
                    NtStatus.NT_STATUS_PASSWORD_EXPIRED,
                    NtStatus.NT_STATUS_NO_SUCH_USER,
                    -> return FileSourceFailure.Unauthorized
                    NtStatus.NT_STATUS_ACCESS_DENIED,
                    NtStatus.NT_STATUS_NETWORK_ACCESS_DENIED,
                    -> return FileSourceFailure.Forbidden
                    NtStatus.NT_STATUS_OBJECT_NAME_NOT_FOUND,
                    NtStatus.NT_STATUS_OBJECT_PATH_NOT_FOUND,
                    NtStatus.NT_STATUS_BAD_NETWORK_NAME,
                    NtStatus.NT_STATUS_NO_SUCH_FILE,
                    NtStatus.NT_STATUS_OBJECT_NAME_INVALID,
                    NtStatus.NT_STATUS_NOT_A_DIRECTORY,
                    -> return FileSourceFailure.NotFound
                }
        }
        if (cause::class.simpleName.orEmpty().contains("Timeout")) return FileSourceFailure.Timeout
    }
    return FileSourceFailure.Unreachable
}

/** What an I/O failure on the WebDAV side means. */
internal fun httpFailureFor(error: IOException): FileSourceFailure =
    when (error) {
        is UnknownHostException, is ConnectException, is NoRouteToHostException -> FileSourceFailure.Unreachable
        is SocketTimeoutException -> FileSourceFailure.Timeout
        is SSLException -> FileSourceFailure.Tls
        is InterruptedIOException -> FileSourceFailure.Timeout
        else -> FileSourceFailure.Unreachable
    }

/**
 * Enqueues rather than executes, so a cancelled browse — the user backed out of a slow folder —
 * cancels the call itself instead of leaving a thread blocked on it.
 */
private suspend fun Call.await(): Response =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(
            object : Callback {
                override fun onFailure(
                    call: Call,
                    e: IOException,
                ) {
                    val failure = httpFailureFor(e)
                    AppLog.warning(
                        category = LOG_CATEGORY,
                        event = "request_failed",
                        message = "A file source request failed before a response",
                        attributes = mapOf("failure" to failure.name, "exception" to e::class.simpleName.orEmpty()),
                    )
                    continuation.resumeWithException(FileSourceException(failure, cause = e))
                }

                override fun onResponse(
                    call: Call,
                    response: Response,
                ) {
                    continuation.resume(response) { _, value, _ -> value.close() }
                }
            },
        )
    }

private fun Response.readBounded(maxBytes: Int): ByteArray {
    val declared = body.contentLength()
    if (declared > maxBytes) throw FileSourceException(FileSourceFailure.TooLarge, status = code)
    return body.byteStream().use { it.readBounded(maxBytes) }
}

private fun InputStream.readBounded(maxBytes: Int): ByteArray {
    val buffer = java.io.ByteArrayOutputStream()
    val chunk = ByteArray(IO_CHUNK_BYTES)
    while (true) {
        val count = read(chunk)
        if (count < 0) break
        if (buffer.size() + count > maxBytes) throw FileSourceException(FileSourceFailure.TooLarge)
        buffer.write(chunk, 0, count)
    }
    return buffer.toByteArray()
}

/** Size and date are part of the key, so a replaced subtitle is fetched again, never served stale. */
internal fun subtitleCacheKey(
    sourceId: String,
    path: List<String>,
    entry: FileSourceEntry,
): String = fileSourceItemId(sourceId, path + "${entry.sizeBytes}:${entry.modifiedEpochMs}").substringAfterLast(':')

/**
 * A folder of many thousands of entries answers PROPFIND with a few megabytes of XML; far beyond
 * that it is not a folder anyone browses on a phone.
 */
private const val MAX_LISTING_BYTES = 24 * 1024 * 1024

/** The largest ASS files — karaoke-heavy anime releases — stay well under this. */
internal const val MAX_SUBTITLE_BYTES = 8 * 1024 * 1024
private const val IO_CHUNK_BYTES = 64 * 1024
private const val MAX_CAUSE_DEPTH = 8
