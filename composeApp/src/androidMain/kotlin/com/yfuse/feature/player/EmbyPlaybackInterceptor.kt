package com.yfuse.feature.player

import com.yfuse.core.network.embyPlaybackHeaders
import com.yfuse.core2.android.mediaCredentialOriginsMatch
import okhttp3.Interceptor
import okhttp3.Response

/** Network interceptor: runs again for every redirect, unlike default request properties. */
internal class EmbyPlaybackInterceptor(
    private val appVersion: () -> String,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.call().request()
        val headers = embyPlaybackHeaders(original.url.toString(), appVersion)
        if (headers.isEmpty()) return chain.proceed(chain.request())

        val request = chain.request()
        val builder = request.newBuilder()
        // OkHttp removes Authorization cross-host, but custom X-Emby-* fields also need stripping.
        // Remove our previous hop's additions even on a same-origin follow-up before recomputing.
        val sameOrigin = mediaCredentialOriginsMatch(original.url.toString(), request.url.toString())
        headers.keys.forEach { name ->
            if (!sameOrigin || original.header(name) == null) builder.removeHeader(name)
        }
        val matchingIdentity =
            request.url.queryParameterNames.all { name ->
                val originalValues =
                    when (name.lowercase()) {
                        "api_key", "apikey", "x-emby-token" -> setOf(headers.getValue("X-Emby-Token"))
                        "userid" ->
                            original.url.queryParameterNames
                                .filter { it.equals("UserId", ignoreCase = true) }
                                .flatMap { original.url.queryParameterValues(it) }
                                .toSet()
                        else -> null
                    }
                originalValues == null || request.url.queryParameterValues(name).all { it in originalValues }
            }
        if (sameOrigin && matchingIdentity) {
            headers.forEach { (name, value) ->
                // Respect an explicit Basic/Bearer header owned by a gateway or data source.
                if (original.header(name) == null) builder.header(name, value)
            }
        }
        return chain.proceed(builder.build())
    }
}
