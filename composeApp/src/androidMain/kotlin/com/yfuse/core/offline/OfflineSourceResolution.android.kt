package com.yfuse.core.offline

import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.model.MediaServerKind
import com.yfuse.core.network.EmbyStream
import com.yfuse.core.network.validateEmbyServerEndpoint
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

internal fun resolveOfflineSourceUrl(
    item: OfflineMedia,
    registry: ServerRegistry,
): String {
    val server =
        registry.serverById(item.serverId)
            ?: error("服务器已移除，无法继续下载")
    require(server.kind != MediaServerKind.Plex) {
        "Plex 离线源必须先通过服务器协商解析"
    }
    // The user rides along as it does for playback, so the transfer can present the same Emby
    // identity (see [openOfflineTransfer]); a server that serves the player serves the download.
    return if (item.quality == OfflineDownloadQuality.Original) {
        EmbyStream.directPlay(
            baseUrl = server.baseUrl,
            itemId = item.itemId,
            token = server.accessToken,
            mediaSourceId = item.mediaSourceId,
            userId = server.userId,
        )
    } else {
        EmbyStream.progressiveTranscode(
            baseUrl = server.baseUrl,
            itemId = item.itemId,
            token = server.accessToken,
            maxWidth = requireNotNull(item.quality.maxWidth),
            videoBitrate = requireNotNull(item.quality.videoBitrateBps),
            mediaSourceId = item.mediaSourceId,
            userId = server.userId,
        )
    }
}

internal suspend fun resolvePlexOfflineSourceUrl(
    item: OfflineMedia,
    server: com.yfuse.core.model.SavedServer,
    repository: EmbyRepository,
): String {
    require(item.quality == OfflineDownloadQuality.Original) {
        "Plex 离线下载当前仅支持原画；转码下载需要 Plex Downloads 转换任务"
    }
    val playback =
        repository
            .playbackInfo(
                server = server,
                itemId = item.itemId,
                mediaSourceId = item.mediaSourceId,
                playSessionId = "offline-${item.id}",
            ).getOrElse { throw it }
    val source =
        playback.MediaSources.firstOrNull { candidate ->
            item.mediaSourceId == null || candidate.Id == item.mediaSourceId
        } ?: error("Plex 中找不到所选媒体版本")
    return source.DirectStreamUrl?.takeIf(String::isNotBlank)
        ?: error("Plex 未返回可下载的原始文件地址")
}

internal suspend fun resolvePlexOfflineSubtitleUrl(
    item: OfflineMedia,
    server: com.yfuse.core.model.SavedServer,
    repository: EmbyRepository,
): String? {
    val index = item.subtitleStreamIndex ?: return null
    val playback =
        repository
            .playbackInfo(
                server = server,
                itemId = item.itemId,
                mediaSourceId = item.mediaSourceId,
                playSessionId = "offline-subtitle-${item.id}",
            ).getOrElse { throw it }
    val source =
        playback.MediaSources.firstOrNull { candidate ->
            item.mediaSourceId == null || candidate.Id == item.mediaSourceId
        } ?: error("Plex 中找不到所选媒体版本")
    return source.MediaStreams
        .orEmpty()
        .firstOrNull { it.Index == index && it.Type.equals("Subtitle", ignoreCase = true) }
        ?.DeliveryUrl
        ?.takeIf(String::isNotBlank)
}

internal fun resolveOfflineSubtitleUrl(
    item: OfflineMedia,
    registry: ServerRegistry,
): String? {
    val index = item.subtitleStreamIndex ?: return null
    val server =
        registry.serverById(item.serverId)
            ?: error("服务器已移除，无法继续下载字幕")
    return EmbyStream.subtitle(
        baseUrl = server.baseUrl,
        itemId = item.itemId,
        mediaSourceId = item.mediaSourceId ?: item.itemId,
        streamIndex = index,
        token = server.accessToken,
        // SRT is broadly readable and asks Emby to convert embedded text subtitles.
        format = "srt",
    )
}

internal fun String?.queryParameter(name: String): String? {
    val query =
        this
            ?.substringAfter('?', missingDelimiterValue = "")
            ?.substringBefore('#')
            .orEmpty()
    return query
        .split('&')
        .asSequence()
        .mapNotNull { part ->
            val key = part.substringBefore('=', missingDelimiterValue = part)
            if (key.equals(name, ignoreCase = true)) part.substringAfter('=', "") else null
        }.firstOrNull()
        ?.takeIf { it.isNotBlank() }
        ?.let { encoded ->
            runCatching { URLDecoder.decode(encoded, Charsets.UTF_8.name()) }.getOrNull()
        }
}

internal fun HttpURLConnection.offlineResumeValidator(): String? {
    val strongEtag =
        getHeaderField("ETag")
            ?.trim()
            ?.takeIf { it.isNotBlank() && !it.startsWith("W/", ignoreCase = true) }
    if (strongEtag != null) return "etag:$strongEtag"
    return getHeaderField("Last-Modified")
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.let { "last-modified:$it" }
}

internal fun String.resumeValidatorHeaderValue(): String = substringAfter(':')

/**
 * Raw offline transfers bypass Ktor, so they validate the user-configured HTTP/HTTPS endpoint
 * before opening a socket. Redirects are followed by hand in [openOfflineTransfer]: the api_key
 * stays in this first URL's query and identity headers stay on its origin, so a hop to another
 * authority never carries the token.
 */
internal fun requireAllowedOfflineTransferUrl(
    value: String,
    localCleartextConfirmed: Boolean,
): URL {
    val url = URL(value)
    require(url.userInfo == null && url.ref == null) { "下载地址不安全" }
    val validation =
        validateEmbyServerEndpoint(
            "${url.protocol}://${url.authority}",
            localCleartextConfirmed,
        )
    require(validation.allowed) { validation.message ?: "下载地址不安全" }
    return url
}
