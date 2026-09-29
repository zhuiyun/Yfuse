package com.yfuse.core.filesource

import com.yfuse.core.network.sharedOriginConnectionPool
import com.yfuse.core.util.androidAppContext
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

actual fun createFileSourceClient(): FileSourceClient =
    AndroidFileSourceClient(
        http = fileSourceHttpClient,
        subtitles = FileSourceSubtitleCache({ androidAppContext?.cacheDir }),
    )

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
