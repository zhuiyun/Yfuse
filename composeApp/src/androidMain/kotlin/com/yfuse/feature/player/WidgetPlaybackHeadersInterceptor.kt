package com.yfuse.feature.player

import com.yfuse.core2.android.mediaCredentialOriginsMatch
import okhttp3.Interceptor
import okhttp3.Response

/** A network interceptor rechecks every redirect and HLS request against the current media origin. */
internal class WidgetPlaybackHeadersInterceptor(
    private val media: () -> PlayerMediaItem?,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val item = media() ?: return chain.proceed(original)
        val headers = item.customHeaders?.values.orEmpty()
        if (headers.isEmpty()) return chain.proceed(original)
        val builder = original.newBuilder()
        val sameOrigin = mediaCredentialOriginsMatch(item.url, original.url.toString())
        headers.forEach { (name, value) ->
            if (sameOrigin) {
                builder.header(name, value)
            } else if (original.header(name) == value) {
                builder.removeHeader(name)
            }
        }
        return chain.proceed(builder.build())
    }
}
