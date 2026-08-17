package com.yfuse.feature.player

import android.net.Uri
import com.yfuse.core.data.ServerRegistry
import com.yfuse.deviceId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

internal fun nativeRemoteBluRayRawDiscUrl(
    baseUrl: String,
    itemId: String,
    mediaSourceId: String,
    playSessionId: String,
): String? {
    val root = baseUrl.trim().trimEnd('/').takeIf(String::isNotEmpty) ?: return null
    val path = "$root/Videos/${Uri.encode(itemId)}/stream"
    return runCatching {
        Uri.parse(path)
            .buildUpon()
            .appendQueryParameter("static", "true")
            .appendQueryParameter("MediaSourceId", mediaSourceId)
            .appendQueryParameter("DeviceId", deviceId())
            .apply {
                playSessionId.takeIf(String::isNotBlank)?.let {
                    appendQueryParameter("PlaySessionId", it)
                }
            }
            .build()
            .toString()
    }.getOrNull()
}
