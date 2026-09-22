package com.yfuse.feature.player

import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.yfuse.core.network.sharedOriginConnectionPool
import com.yfuse.core.platform.AppBuildConfig
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Shared by playback, byte preloading and the compatibility proxy; API sockets use the same pool. */
internal object PlaybackHttpDataSource {
    val client by lazy {
        OkHttpClient
            .Builder()
            .connectionPool(sharedOriginConnectionPool)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .addNetworkInterceptor(EmbyPlaybackInterceptor { AppBuildConfig.VERSION_NAME })
            .build()
    }

    fun factory(
        userAgent: String,
        client: OkHttpClient = this.client,
    ): OkHttpDataSource.Factory =
        OkHttpDataSource.Factory(client).apply {
            userAgent.trim().takeIf(String::isNotEmpty)?.let(::setUserAgent)
        }
}
