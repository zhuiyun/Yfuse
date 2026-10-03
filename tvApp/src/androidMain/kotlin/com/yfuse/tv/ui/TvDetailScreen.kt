package com.yfuse.tv.ui

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.arkivanov.mvikotlin.extensions.coroutines.states
import com.yfuse.core.data.rankServerSources
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.LiftMenu
import com.yfuse.core.designsystem.contentHandoff
import com.yfuse.core.model.Episode
import com.yfuse.core.model.MediaDetail
import com.yfuse.core.model.MediaItem
import com.yfuse.core.model.MediaTrailer
import com.yfuse.core.model.episodePositionForNumber
import com.yfuse.core.model.episodeRangeIndex
import com.yfuse.core.model.episodeRangeLabel
import com.yfuse.core.model.episodeRanges
import com.yfuse.core.model.episodeRuntimeLabel
import com.yfuse.core.model.episodeTitle
import com.yfuse.core.model.prefersEpisodeGrid
import com.yfuse.core.model.typedEpisodeNumberMayGrow
import com.yfuse.core.network.EmbyImages
import com.yfuse.core.network.currentPlaybackNetworkClass
import com.yfuse.core.offline.DownloadStatus
import com.yfuse.core.offline.OfflineMediaManager
import com.yfuse.feature.detail.DetailComponent
import com.yfuse.feature.detail.DetailIntent
import com.yfuse.feature.detail.bestSourcesFirst
import com.yfuse.feature.detail.describing
import com.yfuse.feature.detail.episodeLiftMenu
import com.yfuse.feature.detail.episodeStillUrl
import com.yfuse.feature.detail.relatedLiftMenu
import com.yfuse.feature.detail.rememberEpisodeRowActions
import com.yfuse.feature.detail.seriesProgressConfirmMessage
import com.yfuse.feature.extras.DetailThemeSong
import com.yfuse.feature.extras.TrailerLaunchEffect
import com.yfuse.feature.extras.rememberTrailerLauncher
import com.yfuse.feature.personal.PersonalMediaActions
import com.yfuse.tv.focus.FocusCandidate
import com.yfuse.tv.focus.FocusContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

@Composable
internal fun TvDetailScreen(
    component: DetailComponent,
    focusMemory: TvUiFocusMemory,
) {
    val state by component.store.states.collectAsState(component.store.state)
    val store = component.store
    val detail = state.detail
    val server = state.server
    val playRequester = remember { FocusRequester() }
    val secondaryNavigationRequester = remember { FocusRequester() }
    var sheet by remember(component.itemId) { mutableStateOf<TvDetailSheet?>(null) }
    var seriesPlayedConfirmOpen by remember(component.itemId) { mutableStateOf(false) }
    // 预告片 under the hero's keys and in its preview; 主题曲 plays itself, as on the phone.
    val trailers by component.trailers.collectAsState()
    val trailerLauncher = rememberTrailerLauncher()
    TrailerLaunchEffect(trailerLauncher)
    TvTrailerNoticeTimeout(trailerLauncher)
    DetailThemeSong(component)
    if (detail != null && server != null) {
        // One route per title, so returning from a related title restores this page's own focus.
        val route = tvDetailRoute(detail.id)
        TvRestoreRouteFocusEffect(
            route = route,
            focusMemory = focusMemory,
            fallback = playRequester,
            contentGeneration = listOf(detail.id, state.selectedSeasonId, state.episodes.size, state.related.size),
            context = FocusContext(route, server.id, server.userId),
            exitStableId = tvDetailBackId(detail.id),
        )
    }

    // The page fades in over its loading state rather than cutting in.
    val arrival = Modifier.contentHandoff(state.loading && detail == null)
    when {
        state.loading && detail == null -> TvLoadingState("正在读取详情")
        state.error != null && detail == null ->
            TvEmptyState(
                title = "无法打开详情",
                description = state.error.orEmpty(),
                actionLabel = "重试",
                onAction = { store.accept(DetailIntent.Retry) },
                focusScope = "detail:${component.itemId}:error",
                focusMemory = focusMemory,
                focusRequester = playRequester,
            )
        detail != null && server != null -> {
            val sourceHealth by component.dependencies.serverHealthMonitor.health
                .collectAsState()
            val smartRanking by component.dependencies.playbackPreferences.smartCrossServerSource
                .collectAsState()
            val sourceNetwork = currentPlaybackNetworkClass()
            val selectedVersion =
                state.playTarget?.versions?.firstOrNull { it.id == state.selectedVersionId }
                    ?: state.playTarget?.versions?.firstOrNull()
            val describedSources =
                state.sources.describing(
                    selectedVersion,
                    state.selectedSourceServerId,
                    state.selectedSourceItemId,
                )
            val comparableSources =
                if (smartRanking) {
                    rankServerSources(
                        describedSources,
                        sourceHealth,
                        sourceNetwork,
                    ).map {
                        it.source
                    }
                } else {
                    describedSources.bestSourcesFirst()
                }
            val heroUrl =
                EmbyImages.backdrop(server.baseUrl, detail, accessToken = server.accessToken)
                    ?: EmbyImages.poster(server.baseUrl, detail, accessToken = server.accessToken)
            // Downloading is a library action rather than a detail-store one, so the manager comes
            // from the graph directly instead of widening DetailComponent for one button.
            val offlineMedia = remember { GlobalContext.get().get<OfflineMediaManager>() }
            val offlineItems by offlineMedia.items.collectAsState()
            val downloadTargetId = state.playTarget?.id ?: detail.id
            val existingDownload =
                offlineItems.firstOrNull {
                    it.serverId == (state.playServer ?: server).id && it.itemId == downloadTargetId
                }
            val downloadLabel =
                when (existingDownload?.status) {
                    null -> "下载"
                    DownloadStatus.Completed -> "已下载"
                    DownloadStatus.Failed -> "下载失败"
                    DownloadStatus.Paused -> "已暂停"
                    else -> "下载中"
                }
            val seasonServer = state.playServer ?: server
            // What an episode card's 长按面板 does: the phone's own 单集 rows, over the same store.
            val episodeActions = rememberEpisodeRowActions(component, seasonServer.id)
            // Picks an episode; the one already picked plays.
            val selectEpisode: (Episode) -> Unit = { episode ->
                store.accept(
                    DetailIntent.SelectEpisode(
                        episodeId = episode.id,
                        startPositionTicks = episode.resumePositionTicks ?: 0L,
                    ),
                )
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize().then(arrival),
                contentPadding = PaddingValues(bottom = TvSafeVertical + 38.dp),
                verticalArrangement = Arrangement.spacedBy(26.dp),
            ) {
                item(key = "detail-hero:${detail.id}") {
                    TvDetailHero(
                        detail = detail,
                        heroUrl = heroUrl,
                        playTitle = state.playTarget?.title,
                        resumeTicks = state.playPositionTicks,
                        busy = state.resolvingPlay || state.selectionLoading,
                        onBack = component.onBack,
                        onPlay = {
                            stopTrailerPreview()
                            store.accept(DetailIntent.Play)
                        },
                        onPlayFromStart = {
                            stopTrailerPreview()
                            store.accept(DetailIntent.PlayFromStart)
                        },
                        onToggleFavorite = { store.accept(DetailIntent.ToggleFavorite) },
                        // A series is every episode's watched state and resume point in one press,
                        // so it is asked first, as on the phone; a film or an episode changes at once.
                        onTogglePlayed = {
                            if (detail.type.equals("Series", ignoreCase = true)) {
                                seriesPlayedConfirmOpen = true
                            } else {
                                store.accept(DetailIntent.TogglePlayed)
                            }
                        },
                        watchLater = state.watchLater,
                        watchLaterBusy = state.watchLaterBusy,
                        onToggleWatchLater = { store.accept(DetailIntent.ToggleWatchLater) },
                        onOpenMore = { sheet = TvDetailSheet.More },
                        downloadLabel = downloadLabel,
                        downloadEnabled =
                            state.playTarget != null && state.playServer != null && !state.selectionLoading,
                        onDownload = { sheet = TvDetailSheet.Download },
                        trailers = trailers,
                        onOpenTrailers = {
                            val only = trailers.singleOrNull()
                            if (only != null) {
                                trailerLauncher.openOnTv(only, detail.title)
                            } else {
                                sheet = TvDetailSheet.Trailers
                            }
                        },
                        trailerNotice = trailerLauncher.problem,
                        focusMemory = focusMemory,
                        playRequester = playRequester,
                        serverId = server.id,
                        profileId = server.userId,
                    )
                }

                item(key = "detail-personal:${detail.id}") {
                    // The phone's own buttons, which focus memory does not see: focus reaching them
                    // still ends the page's restore, or episodes arriving later would pull it away.
                    PersonalMediaActions(
                        detail,
                        server.id,
                        Modifier
                            .padding(horizontal = TvSafeHorizontal)
                            .onFocusChanged {
                                if (it.hasFocus) focusMemory.settleRestore(tvDetailRoute(detail.id))
                            },
                    )
                }

                if (state.seasons.isNotEmpty()) {
                    item(key = "detail-seasons:${detail.id}") {
                        Column(
                            Modifier.padding(horizontal = TvSafeHorizontal),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text("季", color = TvOnSurface, fontSize = TvType.section, fontWeight = FontWeight.Bold)
                            LazyRow(
                                modifier = Modifier.tvFocusBleed(),
                                contentPadding = TvFocusBleedPadding,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                itemsIndexed(
                                    state.seasons,
                                    key = { _, season -> "season:${server.id}:${detail.id}:${season.id}" },
                                ) { _, season ->
                                    TvActionButton(
                                        label = season.name,
                                        stableId = "detail:season:${season.id}",
                                        focusScope = "detail:${detail.id}:seasons",
                                        focusMemory = focusMemory,
                                        onClick = { store.accept(DetailIntent.SelectSeason(season.id)) },
                                        modifier = Modifier.width(132.dp),
                                        selected = season.id == state.selectedSeasonId,
                                        selectable = true,
                                        serverId = server.id,
                                        profileId = server.userId,
                                    )
                                }
                            }
                        }
                    }
                }

                if (state.episodesLoading && state.episodes.isEmpty()) {
                    item(key = "detail-episodes-loading") {
                        Box(Modifier.fillMaxWidth().height(150.dp)) { TvLoadingState("正在读取剧集") }
                    }
                } else if (state.episodes.isNotEmpty()) {
                    item(key = "detail-episodes:${state.selectedSeasonId}") {
                        TvEpisodeRow(
                            detail = detail,
                            episodes = state.episodes,
                            selectedEpisodeId = state.selectedEpisodeId,
                            serverId = seasonServer.id,
                            profileId = seasonServer.userId,
                            baseUrl = seasonServer.baseUrl,
                            accessToken = seasonServer.accessToken,
                            focusMemory = focusMemory,
                            onEpisode = selectEpisode,
                            quickActions = { episode ->
                                // 播放, the watched rows, 下载 and 从这里开始多选 — whose 管理进度 is the
                                // television's own sheet. No 查看详情: the card's press picks the episode.
                                episodeLiftMenu(
                                    episode = episode,
                                    episodes = state.episodes,
                                    artworkUrl =
                                        episodeStillUrl(seasonServer.baseUrl, seasonServer.accessToken, episode),
                                    downloaded = episodeActions.downloads.containsKey(episode.id),
                                    onOpen = { selectEpisode(episode) },
                                    onPlay = { episodeActions.play(episode, selectEpisode) },
                                    onMark = episodeActions::mark,
                                    onDownload = { episodeActions.download(listOf(episode)) },
                                    onSelectFrom = {
                                        episodeActions.startSelection(episode)
                                        sheet = TvDetailSheet.EpisodeProgress
                                    },
                                ).withoutOpening()
                            },
                        )
                    }
                }

                val versions = state.playTarget?.versions.orEmpty()
                if (versions.isNotEmpty()) {
                    item(key = "detail-versions:${state.playTarget?.id}") {
                        Column(
                            Modifier.padding(horizontal = TvSafeHorizontal),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text("播放版本", color = TvOnSurface, fontSize = TvType.section, fontWeight = FontWeight.Bold)
                            LazyRow(
                                modifier = Modifier.tvFocusBleed(),
                                contentPadding = TvFocusBleedPadding,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                itemsIndexed(
                                    versions,
                                    key = { _, version ->
                                        "version:${(state.playServer ?: server).id}:${state.playTarget?.id}:${version.id}"
                                    },
                                ) { _, version ->
                                    TvActionButton(
                                        label = version.summary.take(38),
                                        stableId = "detail:version:${version.id}",
                                        focusScope = "detail:${detail.id}:versions",
                                        focusMemory = focusMemory,
                                        onClick = { store.accept(DetailIntent.SelectVersion(version.id)) },
                                        modifier = Modifier.width(260.dp),
                                        selected = version.id == state.selectedVersionId,
                                        selectable = true,
                                        serverId = (state.playServer ?: server).id,
                                        profileId = (state.playServer ?: server).userId,
                                    )
                                }
                            }
                        }
                    }
                }

                if (comparableSources.isNotEmpty()) {
                    item(key = "detail-sources:${detail.id}") {
                        Column(
                            Modifier.padding(horizontal = TvSafeHorizontal),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text("服务器片源", color = TvOnSurface, fontSize = TvType.section, fontWeight = FontWeight.Bold)
                            LazyRow(
                                modifier = Modifier.tvFocusBleed(),
                                contentPadding = TvFocusBleedPadding,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                itemsIndexed(
                                    comparableSources.filter { it.reachable && it.itemId != null },
                                    key = { _, source -> "source:${source.serverId}:${source.itemId}" },
                                ) { _, source ->
                                    TvActionButton(
                                        label =
                                            listOfNotNull(source.serverName, source.source?.quality)
                                                .joinToString(" · "),
                                        stableId = "detail:source:${source.serverId}:${source.itemId}",
                                        focusScope = "detail:${detail.id}:sources",
                                        focusMemory = focusMemory,
                                        onClick = {
                                            source.itemId?.let { id ->
                                                store.accept(DetailIntent.SelectSource(source.serverId, id))
                                            }
                                        },
                                        modifier = Modifier.width(220.dp),
                                        selected =
                                            source.serverId == state.selectedSourceServerId &&
                                                source.itemId == state.selectedSourceItemId,
                                        selectable = true,
                                        serverId = source.serverId,
                                    )
                                }
                            }
                        }
                    }
                }

                if (detail.people.isNotEmpty()) {
                    item(key = "detail-cast:${detail.id}") {
                        TvMediaRow(
                            title = "主演",
                            sectionKey = "detail:${detail.id}:cast",
                            items =
                                detail.people.take(20).mapIndexed { index, person ->
                                    TvMediaCardModel(
                                        stableId = "person:${person.id}:$index",
                                        title = person.name,
                                        subtitle = person.role?.takeIf(String::isNotBlank),
                                        imageUrl =
                                            EmbyImages.avatar(
                                                server.baseUrl,
                                                person,
                                                accessToken = server.accessToken,
                                            ),
                                        serverId = server.id,
                                        profileId = server.userId,
                                        // 演员页: who they are, their titles here, and TMDB's others.
                                        onClick = { component.openPerson(person) },
                                    )
                                },
                            focusMemory = focusMemory,
                            navigationRequester = secondaryNavigationRequester,
                            modifier = Modifier.padding(horizontal = TvSafeHorizontal - 8.dp),
                        )
                    }
                }

                if (state.related.isNotEmpty()) {
                    item(key = "detail-related:${detail.id}") {
                        TvMediaRow(
                            title = "更多相关内容",
                            sectionKey = "detail:${detail.id}:related",
                            items =
                                state.related.map { related ->
                                    related.toRelatedCard(
                                        server = server,
                                        // The phone's 相关推荐 menu, less 分享: nothing to share to here.
                                        quickActions = {
                                            component.relatedLiftMenu(
                                                serverId = server.id,
                                                listed = related,
                                                backdropUrl =
                                                    EmbyImages.backdrop(
                                                        server.baseUrl,
                                                        related,
                                                        accessToken = server.accessToken,
                                                    ),
                                            )
                                        },
                                    ) {
                                        component.onOpenRelated(server.id, related.id)
                                    }
                                },
                            focusMemory = focusMemory,
                            navigationRequester = secondaryNavigationRequester,
                            modifier = Modifier.padding(horizontal = TvSafeHorizontal - 8.dp),
                        )
                    }
                }
            }

            when (sheet) {
                null -> Unit
                TvDetailSheet.Download ->
                    state.playTarget?.let { target ->
                        TvOfflineDownloadDialog(
                            detail = target,
                            episodes = state.episodes,
                            selectedVersionId = state.selectedVersionId,
                            focusMemory = focusMemory,
                            onConfirm = { selection ->
                                component.download(selection)
                                sheet = null
                            },
                            onDismiss = { sheet = null },
                        )
                    }
                TvDetailSheet.More ->
                    TvDetailMoreDialog(
                        component = component,
                        state = state,
                        detail = detail,
                        focusMemory = focusMemory,
                        onOpenSheet = { sheet = it },
                        onDismiss = { sheet = null },
                    )
                TvDetailSheet.Organization ->
                    TvOrganizationDialog(
                        component = component,
                        state = state,
                        focusMemory = focusMemory,
                        onDismiss = { sheet = null },
                    )
                TvDetailSheet.AiringCalendar ->
                    TvAiringCalendarDialog(
                        component = component,
                        detail = detail,
                        focusMemory = focusMemory,
                        onDismiss = { sheet = null },
                    )
                TvDetailSheet.EpisodeProgress ->
                    TvEpisodeProgressDialog(
                        component = component,
                        state = state,
                        focusMemory = focusMemory,
                        onDismiss = { sheet = null },
                    )
                TvDetailSheet.Trailers ->
                    TvTrailerListDialog(
                        title = detail.title,
                        trailers = trailers,
                        focusMemory = focusMemory,
                        onOpen = { trailer ->
                            sheet = null
                            trailerLauncher.openOnTv(trailer, detail.title)
                        },
                        onDismiss = { sheet = null },
                    )
            }

            if (seriesPlayedConfirmOpen) {
                val markPlayed = !detail.played
                TvConfirmDialog(
                    title = if (markPlayed) "整部剧标记为已看？" else "整部剧标记为未看？",
                    message = seriesProgressConfirmMessage(detail.title, state.seasons.size, markPlayed),
                    confirmLabel = if (markPlayed) "标记已看" else "标记未看",
                    focusScope = "detail:series-played",
                    focusMemory = focusMemory,
                    onConfirm = {
                        seriesPlayedConfirmOpen = false
                        store.accept(DetailIntent.TogglePlayed)
                    },
                    onDismiss = { seriesPlayedConfirmOpen = false },
                )
            }
        }
    }
}

@Composable
private fun TvDetailHero(
    detail: MediaDetail,
    heroUrl: String?,
    playTitle: String?,
    resumeTicks: Long,
    busy: Boolean,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onPlayFromStart: () -> Unit,
    onToggleFavorite: () -> Unit,
    onTogglePlayed: () -> Unit,
    watchLater: Boolean,
    watchLaterBusy: Boolean,
    onToggleWatchLater: () -> Unit,
    downloadLabel: String?,
    downloadEnabled: Boolean,
    onDownload: () -> Unit,
    onOpenMore: () -> Unit,
    trailers: List<MediaTrailer>,
    onOpenTrailers: () -> Unit,
    /** Why the last trailer link did not open; see TvTrailerNoticeTimeout. */
    trailerNotice: String?,
    focusMemory: TvUiFocusMemory,
    playRequester: FocusRequester,
    serverId: String,
    profileId: String,
) {
    var heroFocused by remember { mutableStateOf(false) }
    val preview = trailers.firstNotNullOfOrNull { it as? MediaTrailer.Local }
    // Kept whole while focus is anywhere in it: 播放 pivoted on its own would push the title and
    // 返回 off the top on arrival.
    Box(
        Modifier
            .tvKeepWholeInView()
            .fillMaxWidth()
            .height(475.dp)
            .background(TvPlaceholder)
            .onFocusChanged { heroFocused = it.hasFocus },
    ) {
        AsyncImage(
            model = rememberTvImage(heroUrl),
            // Silent: the title is written over it, and the backdrop read it a second time.
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        TvHeroTrailerPreview(
            previewKey = preview?.let { "detail:$serverId:${detail.id}" },
            focused = heroFocused,
            lookup = { preview },
            suspended = busy,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0f to Color.Black.copy(alpha = 0.93f),
                        0.56f to Color.Black.copy(alpha = 0.38f),
                        1f to Color.Transparent,
                    ),
                ).background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.22f),
                        0.68f to Color.Transparent,
                        1f to TvBackground,
                    ),
                ),
        )
        Column(
            Modifier
                .align(Alignment.CenterStart)
                .width(650.dp)
                .padding(start = TvSafeHorizontal),
        ) {
            TvActionButton(
                label = "返回",
                stableId = tvDetailBackId(detail.id),
                focusScope = "detail:${detail.id}:hero",
                focusMemory = focusMemory,
                onClick = onBack,
                modifier = Modifier.width(118.dp),
                icon = AppIcons.ChevronLeft,
                serverId = serverId,
                profileId = profileId,
            )
            Spacer(Modifier.height(20.dp))
            Text(
                detail.title,
                color = Color.White,
                fontSize = TvType.display,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(9.dp))
            Text(
                listOfNotNull(
                    detail.year?.toString(),
                    detail.runtimeMinutes?.let { "$it 分钟" },
                    detail.communityRating?.let { "%.1f 分".format(it) },
                    detail.officialRating,
                    detail.genres
                        .take(3)
                        .joinToString(" / ")
                        .takeIf(String::isNotBlank),
                ).joinToString("  ·  "),
                color = Color.White.copy(alpha = 0.8f),
                fontSize = TvType.caption,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                detail.overview.orEmpty(),
                color = Color.White.copy(alpha = 0.74f),
                fontSize = TvType.caption,
                lineHeight = TvType.readingLineHeight,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                TvActionButton(
                    label =
                        when {
                            busy -> "正在准备"
                            playTitle != null && playTitle != detail.title -> "播放 · $playTitle"
                            resumeTicks > 0L -> "继续播放"
                            else -> "播放"
                        },
                    stableId = "detail:${detail.id}:play",
                    focusScope = "detail:${detail.id}:hero",
                    focusMemory = focusMemory,
                    onClick = onPlay,
                    modifier = Modifier.width(230.dp),
                    icon = AppIcons.Play,
                    primary = true,
                    focusRequester = playRequester,
                    serverId = serverId,
                    profileId = profileId,
                )
                if (resumeTicks > 0L) {
                    TvActionButton(
                        label = "从头播放",
                        stableId = "detail:${detail.id}:restart",
                        focusScope = "detail:${detail.id}:hero",
                        focusMemory = focusMemory,
                        onClick = onPlayFromStart,
                        modifier = Modifier.width(160.dp),
                        serverId = serverId,
                        profileId = profileId,
                    )
                }
            }
            Spacer(Modifier.height(11.dp))
            // Secondary actions sit on their own line, and it scrolls: spelled out in full they are
            // wider than the 650dp hero column, and a plain Row measured the last of them — 更多,
            // with 播出日历 and 进度管理 behind it — to 0dp, unreachable. Shifted left by the inset it
            // pads, so the first button still lines up with 播放.
            LazyRow(
                state = focusMemory.rowState("detail:${detail.id}:hero-actions"),
                modifier = Modifier.offset(x = -TvFocusInset),
                contentPadding = PaddingValues(horizontal = TvFocusInset),
                horizontalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                item(key = "favorite") {
                    TvActionButton(
                        label = if (detail.isFavorite) "服务器已收藏" else "服务器收藏",
                        stableId = "detail:${detail.id}:favorite",
                        focusScope = "detail:${detail.id}:hero",
                        focusMemory = focusMemory,
                        onClick = onToggleFavorite,
                        icon = if (detail.isFavorite) AppIcons.HeartFilled else AppIcons.Heart,
                        selected = detail.isFavorite,
                        serverId = serverId,
                        profileId = profileId,
                    )
                }
                item(key = "played") {
                    TvActionButton(
                        label = if (detail.played) "已看过" else "标记已看",
                        stableId = "detail:${detail.id}:played",
                        focusScope = "detail:${detail.id}:hero",
                        focusMemory = focusMemory,
                        onClick = onTogglePlayed,
                        icon = AppIcons.Check,
                        selected = detail.played,
                        serverId = serverId,
                        profileId = profileId,
                    )
                }
                item(key = "watch-later") {
                    TvActionButton(
                        label = if (watchLater) "服务器已稍后看" else "服务器稍后看",
                        stableId = "detail:${detail.id}:watch-later",
                        focusScope = "detail:${detail.id}:hero",
                        focusMemory = focusMemory,
                        onClick = { if (!watchLaterBusy) onToggleWatchLater() },
                        icon = AppIcons.Bookmark,
                        selected = watchLater,
                        serverId = serverId,
                        profileId = profileId,
                    )
                }
                if (downloadLabel != null) {
                    item(key = "download") {
                        TvActionButton(
                            label = downloadLabel,
                            stableId = "detail:${detail.id}:download",
                            focusScope = "detail:${detail.id}:hero",
                            focusMemory = focusMemory,
                            onClick = onDownload,
                            icon = AppIcons.Download,
                            // Unavailable until a version is resolved: said as 已停用, not dressed
                            // up as a selected state.
                            enabled = downloadEnabled,
                            serverId = serverId,
                            profileId = profileId,
                        )
                    }
                }
                if (trailers.isNotEmpty()) {
                    item(key = "trailer") {
                        TvActionButton(
                            label = "预告片",
                            stableId = "detail:${detail.id}:trailer",
                            focusScope = "detail:${detail.id}:hero",
                            focusMemory = focusMemory,
                            onClick = onOpenTrailers,
                            icon = AppIcons.Movie,
                            serverId = serverId,
                            profileId = profileId,
                        )
                    }
                }
                item(key = "more") {
                    TvActionButton(
                        label = "更多",
                        stableId = "detail:${detail.id}:more",
                        focusScope = "detail:${detail.id}:hero",
                        focusMemory = focusMemory,
                        onClick = onOpenMore,
                        icon = AppIcons.More,
                        serverId = serverId,
                        profileId = profileId,
                    )
                }
            }
            trailerNotice?.let { notice ->
                Spacer(Modifier.height(8.dp))
                Text(notice, color = TvOnSurfaceMuted, fontSize = TvType.caption)
            }
        }
    }
}

@Composable
private fun TvEpisodeRow(
    detail: MediaDetail,
    episodes: List<Episode>,
    selectedEpisodeId: String?,
    serverId: String,
    profileId: String,
    baseUrl: String,
    accessToken: String,
    focusMemory: TvUiFocusMemory,
    onEpisode: (Episode) -> Unit,
    /** The 长按面板 of one episode's card — see [TvQuickActionsPanel]. */
    quickActions: (Episode) -> LiftMenu,
) {
    val episodeScope = "detail:${detail.id}:episodes"
    // A server can list an episode twice, and a lazy row throws on a repeated key.
    val shown = episodes.distinctBy(Episode::id)
    // An episode without a still shows the series poster rather than a blank card.
    val seriesPosterUrl =
        remember(detail, baseUrl, accessToken) {
            EmbyImages.poster(baseUrl, detail, accessToken = accessToken)
        }
    // Kept for the way back from the player. Another season's list starts at the episode picked,
    // or its first: the last season's position carried over opened a shorter season at its tail,
    // and ↓ from the seasons landed on a late episode.
    val rowState =
        focusMemory.rowState(
            section = episodeScope,
            content = shown.firstOrNull()?.id.orEmpty(),
            initialIndex = shown.indexOfFirst { it.id == selectedEpisodeId },
        )
    val candidates =
        shown.mapIndexed { index, episode ->
            val stableId = "server:$serverId:episode:${episode.id}"
            FocusCandidate(
                targetId = focusMemory.targetId(episodeScope, stableId),
                sectionId = episodeScope,
                itemStableId = stableId,
                index = index,
            )
        }
    // 选集 by remote: a long season gets tabs of thirty over the row, the digit keys go to 第几集
    // and the channel keys to the next tab, instead of a hundred presses along the row.
    val numbers = remember(shown) { shown.map(Episode::indexNumber) }
    val ranges = remember(shown.size) { if (prefersEpisodeGrid(shown.size)) episodeRanges(shown.size) else emptyList() }
    val digitJump = shown.size >= DIGIT_JUMP_MIN_EPISODES
    // The tab of the episode last focused, or of the one picked; it changes only as focus crosses
    // into another tab, so moving along the row recomposes nothing here.
    val activeRange by remember(shown, serverId, selectedEpisodeId) {
        derivedStateOf {
            val anchor = focusMemory.anchor(episodeScope)
            val focused = shown.indexOfFirst { "server:$serverId:episode:${it.id}" == anchor }
            episodeRangeIndex(
                focused.takeIf { it >= 0 } ?: shown.indexOfFirst { it.id == selectedEpisodeId }.coerceAtLeast(0),
            )
        }
    }
    val coroutineScope = rememberCoroutineScope()

    fun reveal(position: Int) {
        val episode = shown.getOrNull(position) ?: return
        coroutineScope.launch {
            rowState.scrollToItem(position)
            // The card is composed within a frame or two of the scroll; focus it once it is.
            repeat(EPISODE_FOCUS_ATTEMPTS) {
                withFrameNanos { }
                if (focusMemory.requestFocus(episodeScope, "server:$serverId:episode:${episode.id}")) return@launch
            }
        }
    }
    var typed by remember(shown) { mutableStateOf("") }
    var missing by remember(shown) { mutableStateOf<String?>(null) }
    val highestNumber = remember(numbers) { numbers.filterNotNull().maxOrNull() ?: shown.size }
    LaunchedEffect(typed) {
        val number = typed.toIntOrNull() ?: return@LaunchedEffect
        if (typedEpisodeNumberMayGrow(number, highestNumber)) delay(DIGIT_ENTRY_WINDOW_MS)
        val position = episodePositionForNumber(number, numbers)
        if (position != null) reveal(position) else missing = "没有第 $number 集"
        typed = ""
    }
    LaunchedEffect(missing) {
        if (missing == null) return@LaunchedEffect
        delay(MISSING_EPISODE_NOTICE_MS)
        missing = null
    }
    val route = tvDetailRoute(detail.id)
    val saved = focusMemory.lastForRoute(route, FocusContext(route, serverId, profileId))
    if (saved != null && saved.sectionId == episodeScope) {
        TvRestoreSectionFocusEffect(
            route = route,
            focusMemory = focusMemory,
            saved = saved,
            candidates = candidates,
            contentGeneration = shown.map(Episode::id),
            scrollToAnchor = { anchor ->
                if (candidates.isNotEmpty()) {
                    rowState.revealForRestore(anchor.fallbackIndex.coerceIn(0, candidates.lastIndex))
                }
            },
        )
    }
    Column(
        Modifier
            .padding(horizontal = TvSafeHorizontal)
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                val native = event.nativeKeyEvent
                val digit = remoteDigit(native.keyCode)
                when {
                    digit != null && digitJump -> {
                        // A leading 0 names no episode; it is swallowed rather than typed.
                        if (typed.isNotEmpty() || digit != 0) typed = (typed + digit).takeLast(MAX_TYPED_DIGITS)
                        true
                    }
                    ranges.size > 1 && native.repeatCount == 0 && native.keyCode in NextRangeKeys -> {
                        ranges.getOrNull(activeRange + 1)?.let { reveal(it.first) }
                        true
                    }
                    ranges.size > 1 && native.repeatCount == 0 && native.keyCode in PreviousRangeKeys -> {
                        ranges.getOrNull(activeRange - 1)?.let { reveal(it.first) }
                        true
                    }
                    else -> false
                }
            },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("剧集", color = TvOnSurface, fontSize = TvType.section, fontWeight = FontWeight.Bold)
            val entry = if (typed.isNotEmpty()) "第 $typed 集" else missing
            if (entry != null) {
                Text(entry, color = TvAccent, fontSize = TvType.body, fontWeight = FontWeight.SemiBold)
            }
        }
        if (ranges.size > 1) {
            LazyRow(
                modifier = Modifier.tvFocusBleed(),
                contentPadding = TvFocusBleedPadding,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                itemsIndexed(ranges, key = { index, _ -> "episode-range:${detail.id}:$index" }) { index, range ->
                    TvActionButton(
                        label = episodeRangeLabel(range) { numbers[it] },
                        stableId = "detail:${detail.id}:episode-range:$index",
                        focusScope = "detail:${detail.id}:episode-ranges",
                        focusMemory = focusMemory,
                        onClick = { reveal(range.first) },
                        modifier = Modifier.width(120.dp),
                        selected = index == activeRange,
                        selectable = true,
                        serverId = serverId,
                        profileId = profileId,
                    )
                }
            }
        }
        // A card row like the shelves: the focused episode rests a third of the way in.
        ProvideTvRowPivot {
            LazyRow(
                state = rowState,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 9.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                itemsIndexed(shown, key = { _, episode -> "episode:$serverId:${detail.id}:${episode.id}" }) {
                        index,
                        episode,
                    ->
                    TvMediaCard(
                        model =
                            TvMediaCardModel(
                                stableId = "server:$serverId:episode:${episode.id}",
                                title = episodeTitle(episode.indexNumber, episode.name) { "第 $it 集" },
                                subtitle =
                                    listOfNotNull(
                                        episodeRuntimeLabel(episode.runtimeTicks, episode.runtimeMinutes),
                                        when {
                                            episode.played -> "已看"
                                            (episode.playedPercentage ?: 0.0) > 0.0 -> "继续观看"
                                            else -> null
                                        },
                                    ).joinToString(" · "),
                                imageUrl =
                                    EmbyImages.primary(
                                        baseUrl,
                                        episode.id,
                                        episode.primaryTag,
                                        maxHeight = 300,
                                        accessToken = accessToken,
                                    ),
                                imageFallbackUrls = listOf(seriesPosterUrl),
                                serverId = serverId,
                                profileId = profileId,
                                progress = episode.playedPercentage?.div(100.0)?.toFloat(),
                                artworkShape = TvArtworkShape.Landscape,
                                selected = episode.id == selectedEpisodeId,
                                selectable = true,
                                quickActions = { quickActions(episode) },
                                onClick = { onEpisode(episode) },
                            ),
                        focusScope = episodeScope,
                        focusMemory = focusMemory,
                        fallbackIndex = index,
                    )
                }
            }
        }
        Text(
            when {
                ranges.size > 1 -> "按一次选择剧集，再按一次直接播放 · 数字键跳到第几集，频道键翻页"
                digitJump -> "按一次选择剧集，再按一次直接播放 · 数字键跳到第几集"
                else -> "按一次选择剧集，再按一次直接播放"
            },
            color = TvOnSurfaceMuted,
            fontSize = TvType.caption,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/** From this many episodes the digit keys jump to 第几集; a shorter row is quicker to walk. */
private const val DIGIT_JUMP_MIN_EPISODES = 10

/** How long a digit waits for the next one of the same number before the row jumps. */
private const val DIGIT_ENTRY_WINDOW_MS = 1_200L

private const val MISSING_EPISODE_NOTICE_MS = 1_500L

private const val MAX_TYPED_DIGITS = 4

/** Frames to wait for a card scrolled to before giving up on focusing it. */
private const val EPISODE_FOCUS_ATTEMPTS = 4

/** The next tab of thirty: CH+, page down, fast-forward or next. */
private val NextRangeKeys =
    setOf(
        KeyEvent.KEYCODE_CHANNEL_UP,
        KeyEvent.KEYCODE_PAGE_DOWN,
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
        KeyEvent.KEYCODE_MEDIA_NEXT,
    )

private val PreviousRangeKeys =
    setOf(
        KeyEvent.KEYCODE_CHANNEL_DOWN,
        KeyEvent.KEYCODE_PAGE_UP,
        KeyEvent.KEYCODE_MEDIA_REWIND,
        KeyEvent.KEYCODE_MEDIA_PREVIOUS,
    )

/** The digit a remote's number key or a keypad key stands for; null for any other key. */
private fun remoteDigit(keyCode: Int): Int? =
    when (keyCode) {
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> keyCode - KeyEvent.KEYCODE_0
        in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> keyCode - KeyEvent.KEYCODE_NUMPAD_0
        else -> null
    }

private fun MediaItem.toRelatedCard(
    server: com.yfuse.core.model.SavedServer,
    quickActions: () -> LiftMenu,
    onClick: () -> Unit,
): TvMediaCardModel =
    TvMediaCardModel(
        stableId = "${server.kind.name.lowercase()}:${server.id}:related:$id",
        title = title,
        subtitle = year?.toString(),
        imageUrl = EmbyImages.poster(server.baseUrl, this, accessToken = server.accessToken),
        serverId = server.id,
        profileId = server.userId,
        badge = communityRating?.let { "%.1f".format(it) },
        quickActions = quickActions,
        onClick = onClick,
    )

/** A detail page's own 返回 — see [TvRestoreRouteFocusEffect]'s `exitStableId`. */
private fun tvDetailBackId(itemId: String): String = "detail:$itemId:back"
