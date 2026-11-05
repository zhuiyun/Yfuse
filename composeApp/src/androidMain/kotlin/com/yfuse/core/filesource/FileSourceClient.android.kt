package com.yfuse.core.filesource

import com.yfuse.core.cloud.AndroidCloudDriveClient
import com.yfuse.core.network.sharedOriginConnectionPool
import com.yfuse.core.util.androidAppContext
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

actual fun createFileSourceClient(): FileSourceClient {
    val subtitles = FileSourceSubtitleCache({ androidAppContext?.cacheDir })
    val dav = AndroidFileSourceClient(http = fileSourceHttpClient, subtitles = subtitles)
    val ftp = FtpFileSourceClient(subtitles)
    val cloud = AndroidCloudDriveClient(fileSourceHttpClient, subtitles)
    return object : FileSourceClient {
        fun delegate(source: FileSource): FileSourceClient =
            when (source.kind) {
                FileSourceKind.Ftp -> ftp
                FileSourceKind.Pan123, FileSourceKind.Baidu -> cloud
                else -> dav
            }

        override suspend fun list(
            source: FileSource,
            credentials: FileSourceCredentials,
            path: List<String>,
        ) = delegate(source).list(source, credentials, path)

        override suspend fun listBounded(
            source: FileSource,
            credentials: FileSourceCredentials,
            path: List<String>,
            maxEntries: Int,
        ) = delegate(source).listBounded(source, credentials, path, maxEntries)

        override suspend fun cacheSubtitle(
            source: FileSource,
            credentials: FileSourceCredentials,
            path: List<String>,
            entry: FileSourceEntry,
        ) = delegate(source).cacheSubtitle(source, credentials, path, entry)

        override fun capabilities(source: FileSource) = delegate(source).capabilities(source)

        override suspend fun move(
            source: FileSource,
            credentials: FileSourceCredentials,
            from: List<String>,
            to: List<String>,
            directory: Boolean,
        ) = delegate(source).move(source, credentials, from, to, directory)

        override suspend fun resolvePlayback(
            source: FileSource,
            credentials: FileSourceCredentials,
            path: List<String>,
        ) = delegate(source).resolvePlayback(source, credentials, path)
    }
}

/**
 * The media transport's connection pool, so the TLS session a listing opens to a share is the one
 * the first byte range of playback reuses. The read timeout is generous on purpose: an Alist
 * listing a 网盘 folder for the first time waits on the drive's own API before it answers.
 */
private val fileSourceHttpClient: OkHttpClient by lazy {
    OkHttpClient
        .Builder()
        .connectionPool(sharedOriginConnectionPool)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()
}

private const val CONNECT_TIMEOUT_SECONDS = 10L
private const val READ_TIMEOUT_SECONDS = 30L
private const val CALL_TIMEOUT_SECONDS = 60L
