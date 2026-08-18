package com.yfuse.feature.player

import com.yfuse.core.data.ServerRegistry
import com.yfuse.deviceId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Credential-free description of one raw remote Blu-ray origin to probe. */
internal data class NativeRemoteBluRayPreflightRequest(
    val serverId: String,
    val itemId: String,
    val mediaSourceId: String,
    val playSessionId: String,
)

/**
 * Proves that the exact raw ISO endpoint honors byte ranges before YCore replaces the safe server
 * fallback with `yfusebd://`. The probe runs on Dispatchers.IO because HttpURLConnection is blocking
 * and the range reader deliberately rejects Android-main-thread I/O.
 *
 * Credentials are resolved inside [RemoteDiscHeaderProvider] for the request and never become part of
 * the URL or result. A failed probe simply leaves the existing server main-feature/transcode route in
 * place; it is not a playback error by itself.
 */
internal suspend fun probeNativeRemoteBluRayRangeSupport(
    request: NativeRemoteBluRayPreflightRequest,
    serverRegistry: ServerRegistry,
): Boolean =
    withContext(Dispatchers.IO) {
        val server = serverRegistry.serverById(request.serverId) ?: return@withContext false
        val sourceUrl =
            nativeRemoteBluRayRawDiscUrl(
                baseUrl = server.baseUrl,
                itemId = request.itemId,
                mediaSourceId = request.mediaSourceId,
                playSessionId = request.playSessionId,
            ) ?: return@withContext false
        val headers =
            RemoteDiscHeaderProvider {
                val current = serverRegistry.serverById(request.serverId)
                    ?: return@RemoteDiscHeaderProvider emptyMap()
                buildMap {
                    current.accessToken.takeIf(String::isNotBlank)?.let { put("X-Emby-Token", it) }
                }
            }
        HttpRangeDiscBlockSource(
            sourceUrl = sourceUrl,
            headerProvider = headers,
        ).use(HttpRangeDiscBlockSource::probeRangeSupport)
    }

/**
 * Builds the credential-free raw-disc address used by the Range reader.
 *
 * Authentication is intentionally header-only. The builder uses JDK URL encoding instead of
 * `android.net.Uri`, keeping the security contract executable in plain JVM unit tests as well as on
 * device. `%20` is used for spaces so path/query components stay RFC-3986-friendly instead of form
 * encoding's `+` representation.
 */
internal fun nativeRemoteBluRayRawDiscUrl(
    baseUrl: String,
    itemId: String,
    mediaSourceId: String,
    playSessionId: String,
    deviceIdentifier: String = deviceId(),
): String? {
    val root = baseUrl.trim().trimEnd('/').takeIf(String::isNotEmpty) ?: return null
    val scheme = root.substringBefore("://", "").lowercase()
    if (scheme !in setOf("http", "https")) return null
    val query =
        buildList {
            add("static=true")
            add("MediaSourceId=${mediaSourceId.urlComponent()}")
            add("DeviceId=${deviceIdentifier.urlComponent()}")
            playSessionId.takeIf(String::isNotBlank)?.let {
                add("PlaySessionId=${it.urlComponent()}")
            }
        }.joinToString("&")
    return "$root/Videos/${itemId.urlComponent()}/stream?$query"
}

private fun String.urlComponent(): String =
    URLEncoder
        .encode(this, StandardCharsets.UTF_8.name())
        .replace("+", "%20")
