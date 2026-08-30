package com.yfuse.feature.player

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import com.yfuse.core.playback.PlaybackDrmConfiguration
import com.yfuse.core.playback.PlaybackDrmScheme

/** Keeps a failing address useful in diagnostics without exporting the user's server token. */
internal fun sanitizePlaybackUrl(value: String): String {
    val querySafe =
        value.replace(
            Regex("(?i)(api_key|x-emby-token|x-plex-token)=([^&\\s]+)"),
        ) { match -> "${match.groupValues[1]}=<redacted>" }
    return querySafe.replace(
        Regex("(?i)(\"?(?:api_key|x-emby-token|x-plex-token)\"?\\s*:\\s*\")([^\"]+)(\")"),
    ) { match -> "${match.groupValues[1]}<redacted>${match.groupValues[3]}" }
}

internal fun playbackQueryParameter(
    url: String,
    name: String,
): String? =
    Regex("(?:[?&])${Regex.escape(name)}=([^&]+)", RegexOption.IGNORE_CASE)
        .find(url)
        ?.groupValues
        ?.getOrNull(1)
        ?.takeIf(String::isNotBlank)

/** An account or edge-policy rejection applies to every URL/engine for this server. */
internal fun blocksAutomaticPlaybackFallback(httpStatus: Int?): Boolean = httpStatus == 401 || httpStatus == 403

internal fun httpFailureMessage(
    status: Int?,
    body: String?,
): String =
    when (status) {
        401 -> "服务器登录已失效（401），请重新登录该服务器"
        403 ->
            if (body.isAccessBlockPage()) {
                "服务器入口或 Cloudflare 拒绝了当前网络访问（403），重新登录通常无效"
            } else {
                "当前账号没有播放权限，或服务器入口拒绝了访问（403）"
            }
        400 -> "服务器无法处理当前版本的转码请求（400），正在尝试其他播放方式"
        404 -> "服务器上找不到这个文件（404）"
        429, 503 -> "服务器暂时无法提供转码（$status），请稍后再试"
        null -> "服务器拒绝了播放请求"
        else -> "服务器拒绝了播放请求（$status）"
    }

internal fun exoMediaItem(
    item: PlayerMediaItem,
    playbackUrl: String,
): MediaItem {
    val builder =
        MediaItem
            .Builder()
            .setUri(playbackUrl)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(item.title).build())
    exoContainerMimeType(
        declaredContainer = item.activeVersion?.container,
        playbackUrl = playbackUrl,
        originalStream = playbackUrl == item.url,
    )?.let(builder::setMimeType)
    playbackDrmConfiguration(item, playbackUrl)
        ?.let { builder.setDrmConfiguration(it.toMedia3Configuration()) }
    externalSubtitleConfigurations(item)
        .takeIf { it.isNotEmpty() }
        ?.let(builder::setSubtitleConfigurations)
    return builder.build()
}

/** Forces the progressive TS extractor when a resolved server URL has no useful extension. */
internal fun exoContainerMimeType(
    declaredContainer: String?,
    playbackUrl: String,
    originalStream: Boolean,
): String? {
    val path = playbackUrl.substringBefore('?').substringBefore('#').lowercase()
    if (path.endsWith(".m3u8") || path.endsWith(".mpd")) return null
    val urlIsTs = path.endsWith(".ts") || path.endsWith(".m2ts") || path.endsWith(".mts")
    val declaredIsTs =
        declaredContainer
            ?.trim()
            ?.lowercase() in setOf("ts", "m2ts", "mts", "mpegts", "mpeg-ts")
    return MimeTypes.VIDEO_MP2T.takeIf { urlIsTs || (originalStream && declaredIsTs) }
}

/** Original-stream keys must never be forwarded to an independently encrypted server transcode. */
internal fun playbackDrmConfiguration(
    item: PlayerMediaItem,
    playbackUrl: String,
): PlaybackDrmConfiguration? =
    (item.drmConfiguration ?: item.activeVersion?.drmConfiguration)
        ?.takeIf { playbackUrl == item.url }

/** Converts secrets only at the final Media3 boundary; no diagnostic object receives them. */
internal fun PlaybackDrmConfiguration.toMedia3Configuration(): MediaItem.DrmConfiguration {
    val uuid =
        when (scheme) {
            PlaybackDrmScheme.Widevine -> C.WIDEVINE_UUID
            PlaybackDrmScheme.ClearKey -> C.CLEARKEY_UUID
            PlaybackDrmScheme.PlayReady -> C.PLAYREADY_UUID
        }
    return MediaItem.DrmConfiguration
        .Builder(uuid)
        .apply {
            licenseUri?.takeIf(String::isNotBlank)?.let(::setLicenseUri)
            setLicenseRequestHeaders(requestHeaders)
            setMultiSession(multiSession)
            setForceDefaultLicenseUri(forceDefaultLicenseUri)
            setPlayClearContentWithoutKey(playClearContentWithoutKey)
            offlineKeySetId?.copyOf()?.let(::setKeySetId)
        }.build()
}

internal fun externalSubtitleFormatHint(uri: String): String? {
    val normalized = uri.substringBefore('#')
    val path = normalized.substringBefore('?')
    val fileName = path.substringAfterLast('/')
    val extension =
        fileName
            .substringAfterLast('.', missingDelimiterValue = "")
            .trim()
            .lowercase()
            .takeIf { it.matches(Regex("[a-z0-9]{2,8}")) }
    if (extension != null) return extension
    return sequenceOf("format", "codec", "extension")
        .mapNotNull { playbackQueryParameter(normalized, it) }
        .map { it.trim().lowercase() }
        .firstOrNull { it.matches(Regex("[a-z0-9]{2,8}")) }
}

internal fun externalSubtitleMimeType(
    uri: String,
    codec: String? = null,
): String =
    when (codec?.trim()?.lowercase() ?: externalSubtitleFormatHint(uri)) {
        "vtt", "webvtt" -> MimeTypes.TEXT_VTT
        "ass", "ssa" -> MimeTypes.TEXT_SSA
        "ttml", "dfxp" -> MimeTypes.APPLICATION_TTML
        else -> MimeTypes.APPLICATION_SUBRIP
    }

internal fun offlineSubtitleConfiguration(item: PlayerMediaItem): MediaItem.SubtitleConfiguration? {
    val uri = item.externalSubtitleUri?.takeIf(String::isNotBlank) ?: return null
    return MediaItem.SubtitleConfiguration
        .Builder(android.net.Uri.parse(uri))
        .setMimeType(externalSubtitleMimeType(uri))
        .setLanguage(item.externalSubtitleLanguage?.takeIf(String::isNotBlank))
        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
        .build()
}

internal fun externalSubtitleConfigurations(item: PlayerMediaItem): List<MediaItem.SubtitleConfiguration> =
    item.playbackExternalSubtitles().map { subtitle ->
        val selectionFlags =
            (if (subtitle.default) C.SELECTION_FLAG_DEFAULT else 0) or
                (if (subtitle.forced) C.SELECTION_FLAG_FORCED else 0)
        MediaItem.SubtitleConfiguration
            .Builder(android.net.Uri.parse(subtitle.uri))
            .setMimeType(externalSubtitleMimeType(subtitle.uri, subtitle.codec))
            .setLanguage(subtitle.language?.takeIf(String::isNotBlank))
            .setSelectionFlags(selectionFlags)
            .build()
    }

internal fun mediaItem(
    item: PlayerMediaItem,
    playbackUrl: String,
): MediaItem = exoMediaItem(item, playbackUrl)

private fun String?.isAccessBlockPage(): Boolean {
    val value = this?.lowercase().orEmpty()
    return "cloudflare" in value ||
        "sorry, you have been blocked" in value ||
        "access denied" in value ||
        "attention required" in value
}
