package com.yfuse.feature.detail

import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.model.Episode
import com.yfuse.core.model.MediaDetail
import com.yfuse.core.model.SavedServer
import com.yfuse.core.model.Season
import com.yfuse.core.network.EmbyError
import com.yfuse.core.network.EmbyErrorException
import kotlinx.coroutines.delay

sealed interface SourceSelectionFailure {
    data object Timeout : SourceSelectionFailure

    data object NetworkUnavailable : SourceSelectionFailure

    data object AuthRequired : SourceSelectionFailure

    data class AccessDenied(
        val provider: String?,
    ) : SourceSelectionFailure

    data class Server(
        val code: Int,
    ) : SourceSelectionFailure

    data class EpisodeMissing(
        val season: Int?,
        val episode: Int?,
    ) : SourceSelectionFailure

    data object InvalidResponse : SourceSelectionFailure
}

internal class SourceSelectionTimeoutException : Exception("Cross-server source selection timed out")

internal class EpisodeUnavailableException(
    val seasonNumber: Int?,
    val episodeNumber: Int?,
) : Exception("The selected source does not contain the current episode")

internal fun Throwable.toSourceSelectionFailure(): SourceSelectionFailure =
    when (this) {
        is SourceSelectionTimeoutException -> SourceSelectionFailure.Timeout
        is EpisodeUnavailableException -> SourceSelectionFailure.EpisodeMissing(seasonNumber, episodeNumber)
        else ->
            when (val error = (this as? EmbyErrorException)?.error) {
                EmbyError.Network -> SourceSelectionFailure.NetworkUnavailable
                EmbyError.Unauthorized -> SourceSelectionFailure.AuthRequired
                is EmbyError.AccessDenied -> SourceSelectionFailure.AccessDenied(error.provider)
                is EmbyError.Server -> SourceSelectionFailure.Server(error.code)
                else -> SourceSelectionFailure.InvalidResponse
            }
    }

internal fun SourceSelectionFailure.toDetailMessage(): String =
    when (this) {
        SourceSelectionFailure.Timeout -> "资源切换等待超时，请检查网络后再试"
        SourceSelectionFailure.NetworkUnavailable -> "资源服务器暂时无法连接，已保留当前播放版本"
        SourceSelectionFailure.AuthRequired -> "资源服务器登录已失效，请到服务器管理重新登录"
        is SourceSelectionFailure.AccessDenied ->
            if (provider == "Cloudflare") {
                "访问被 Cloudflare 拦截，请更换网络或联系服务器管理员"
            } else {
                "资源服务器拒绝访问，请检查防火墙或反向代理访问策略"
            }
        is SourceSelectionFailure.Server -> "资源服务器暂时异常（HTTP $code），请稍后再试"
        is SourceSelectionFailure.EpisodeMissing -> {
            val coordinate = if (season != null && episode != null) "第 $season 季第 $episode 集" else "当前剧集"
            "该资源没有$coordinate，请选择其他播放版本"
        }
        SourceSelectionFailure.InvalidResponse -> "资源信息无法解析，请刷新后重试"
    }

/** Retry policy is isolated from the Store so source selection can be unit-tested independently. */
internal class SourceSelectionCoordinator(
    private val repo: EmbyRepository,
    private val maxAttempts: Int = 3,
    private val retryBaseDelayMs: Long = 250L,
) {
    suspend fun <T> resolve(
        server: SavedServer,
        sourceItemId: String,
        stillCurrent: () -> Boolean,
        resolveDetail: suspend (MediaDetail) -> Result<T>,
    ): Result<T> {
        var attempt = 1
        while (true) {
            val result =
                repo.itemDetail(server, sourceItemId).fold(
                    onSuccess = { resolveDetail(it) },
                    onFailure = { Result.failure(it) },
                )
            val failure = result.exceptionOrNull()
            if (result.isSuccess || attempt >= maxAttempts || failure?.isTransientSourceFailure() != true) return result
            delay(retryBaseDelayMs shl (attempt - 1))
            if (!stillCurrent()) return result
            attempt++
        }
    }
}

internal data class SeriesCatalog(
    val seasons: List<Season>,
    val selectedSeasonId: String?,
    val episodes: List<Episode>,
)

internal class SeriesCatalogLoader(
    private val repo: EmbyRepository,
) {
    suspend fun load(
        server: SavedServer,
        seriesId: String,
        target: MediaDetail,
        allEpisodes: List<Episode>?,
    ): SeriesCatalog {
        val seasons = repo.seasons(server, seriesId).getOrThrow()
        val targetEpisode = allEpisodes?.firstOrNull { it.id == target.id }
        val selectedSeasonId =
            targetEpisode?.seasonId
                ?: seasons.firstOrNull { it.indexNumber == target.seasonNumber }?.id
                ?: seasons.firstOrNull()?.id
        val episodes =
            allEpisodes
                ?.filter { selectedSeasonId == null || it.seasonId == selectedSeasonId }
                ?: repo
                    .episodes(
                        server = server,
                        seriesId = seriesId,
                        seasonId = selectedSeasonId,
                        includeMediaSources = true,
                    ).getOrThrow()
        return SeriesCatalog(seasons, selectedSeasonId, episodes)
    }
}
