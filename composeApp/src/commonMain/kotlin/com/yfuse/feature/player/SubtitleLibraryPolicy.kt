package com.yfuse.feature.player

import com.yfuse.core.data.dto.RemoteSubtitleInfoDto
import com.yfuse.core.model.MediaServerKind
import com.yfuse.core.model.capabilities
import com.yfuse.core.network.toUserMessage

enum class SubtitleSearchLanguage(
    val code: String,
    val label: String,
) {
    Chinese("zh", "中文"),
    English("en", "英语"),
    Japanese("ja", "日语"),
    Korean("ko", "韩语"),
    Spanish("es", "西班牙语"),
    French("fr", "法语"),
    German("de", "德语"),
}

internal fun subtitleSearchUnavailableReason(kind: MediaServerKind?): String? =
    when {
        kind == null -> "此离线内容无法访问服务器字幕商店，可导入本地字幕。"
        !kind.capabilities().subtitleStore -> "此服务器不提供字幕商店；可导入本地字幕，或在服务器中添加后重新打开影片。"
        else -> null
    }

/** Import ownership includes the server and version: equal item ids are not globally unique. */
internal data class SubtitleItemKey(
    val serverId: String?,
    val itemId: String,
    val versionId: String?,
)

internal fun PlayerMediaItem.subtitleItemKey(): SubtitleItemKey = SubtitleItemKey(serverId, id, versionId)

internal fun PlayerMediaItem.withImportedSubtitles(
    imports: Map<SubtitleItemKey, List<PlayerExternalSubtitle>>,
): PlayerMediaItem {
    val sidecars = imports[subtitleItemKey()].orEmpty()
    return if (sidecars.isEmpty()) {
        this
    } else {
        copy(
            externalSubtitles = (externalSubtitles + sidecars).distinctBy(PlayerExternalSubtitle::uri),
        )
    }
}

internal fun RemoteSubtitlePanelState.withSearchResult(
    result: Result<List<RemoteSubtitleInfoDto>>,
    language: SubtitleSearchLanguage,
): RemoteSubtitlePanelState =
    result.fold(
        onSuccess = { results ->
            val options =
                results.map { entry ->
                    RemoteSubtitleOption(
                        id = entry.Id,
                        label = entry.Name ?: entry.Language ?: "${language.label}字幕",
                        detail = listOfNotNull(entry.ProviderName, entry.Format?.uppercase()).joinToString(" · "),
                    )
                }
            copy(
                loading = false,
                results = options,
                message = "没有找到${language.label}字幕；可切换语言、检查服务器字幕提供商配置，或导入本地字幕。".takeIf { results.isEmpty() },
            )
        },
        onFailure = { copy(loading = false, message = it.toUserMessage("字幕搜索失败，请重试。")) },
    )
