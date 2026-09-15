package com.yfuse.feature.detail

import com.yfuse.core.data.PlaybackNetworkClass
import com.yfuse.core.data.ServerHealth
import com.yfuse.core.data.serverSourcePlayable
import com.yfuse.core.data.sourceRecommendationReasons
import com.yfuse.core.model.ServerSource

internal data class SourceSelectionPresentation(
    val selectedLabel: String,
    val recommendationLabel: String,
    val reason: String,
    val recommendedServerId: String?,
    val recommendedItemId: String?,
)

internal fun sourceSelectionPresentation(
    sources: List<ServerSource>,
    selectedServerId: String?,
    selectedItemId: String?,
    selectedVersionName: String?,
    health: Map<String, ServerHealth>,
    network: PlaybackNetworkClass,
    smartRanking: Boolean,
): SourceSelectionPresentation {
    val selected = sources.firstOrNull { it.serverId == selectedServerId && it.itemId == selectedItemId }
    val recommended = sources.firstOrNull { serverSourcePlayable(it, health[it.serverId]) }
    return SourceSelectionPresentation(
        selectedLabel =
            "当前选择：" +
                listOfNotNull(
                    selected?.serverName,
                    selectedVersionName?.takeIf(String::isNotBlank),
                ).joinToString(" · ").ifBlank {
                    "正在读取来源"
                },
        recommendationLabel = recommended?.let { "推荐参考：${it.serverName}" } ?: "暂无可推荐来源，请检查服务器连接。",
        reason =
            recommended
                ?.let {
                    sourceRecommendationReasons(it, health[it.serverId], network, smartRanking).joinToString(" · ")
                }.orEmpty(),
        recommendedServerId = recommended?.serverId,
        recommendedItemId = recommended?.itemId,
    )
}
