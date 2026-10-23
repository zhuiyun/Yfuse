package com.yfuse.feature.player

import com.yfuse.core2.android.mediaCredentialOriginsMatch
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.net.CookieManager
import java.net.CookiePolicy

/** A request owns its item snapshot, even while another queue item is playing or preloading. */
internal class WidgetPlaybackRequest(
    val origin: String,
    val headers: PlayerCustomHeaders,
) {
    val cookies = CookieManager(null, CookiePolicy.ACCEPT_ALL)

    override fun toString(): String = "WidgetPlaybackRequest([redacted])"
}

internal fun PlayerMediaItem.widgetPlaybackRequest(): WidgetPlaybackRequest? =
    customHeaders?.takeIf { it.values.isNotEmpty() }?.let { WidgetPlaybackRequest(url, it) }

/** Network interceptors run again for every redirect; request tags survive OkHttp follow-ups. */
internal class WidgetPlaybackHeadersInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val binding = original.tag(WidgetPlaybackRequest::class.java) ?: return chain.proceed(original)
        val builder = original.newBuilder()
        val sameOrigin = mediaCredentialOriginsMatch(binding.origin, original.url.toString())
        binding.headers.values.forEach { (name, value) ->
            if (sameOrigin) {
                builder.header(name, value)
            } else if (original.header(name) == value) {
                builder.removeHeader(name)
            }
        }
        return chain.proceed(builder.build())
    }
}

internal fun widgetPlaybackHttpClient(binding: WidgetPlaybackRequest): OkHttpClient =
    PlaybackHttpDataSource.client
        .newBuilder()
        .addInterceptor { chain ->
            chain.proceed(
                chain
                    .request()
                    .newBuilder()
                    .tag(WidgetPlaybackRequest::class.java, binding)
                    .build(),
            )
        }.addNetworkInterceptor(WidgetPlaybackHeadersInterceptor())
        .build()
