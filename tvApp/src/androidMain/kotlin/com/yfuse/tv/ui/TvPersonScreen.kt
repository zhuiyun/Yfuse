package com.yfuse.tv.ui

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.model.MediaItem
import com.yfuse.core.model.SavedServer
import com.yfuse.core.model.TmdbItem
import com.yfuse.core.network.EmbyImages
import com.yfuse.core.network.TmdbImages
import com.yfuse.core.util.currentIsoDate
import com.yfuse.feature.person.PersonComponent
import com.yfuse.feature.person.PersonPageState
import com.yfuse.feature.person.departmentLabel
import com.yfuse.feature.person.personBirthPlaceLine
import com.yfuse.feature.person.personLifeLine

/**
 * 演员页 on the television: who the person is, a row of what this library holds of theirs — each
 * opening its detail page, where it plays — and a row of what else TMDB credits them on, opening
 * the TMDB page. Back returns to the detail page whose cast row opened it.
 */
@Composable
internal fun TvPersonScreen(
    component: PersonComponent,
    focusMemory: TvUiFocusMemory,
) {
    val state by component.state.collectAsState()
    val route = tvPersonRoute(component.request.personId)
    val backRequester = remember { FocusRequester() }
    TvRestoreRouteFocusEffect(
        route = route,
        focusMemory = focusMemory,
        fallback = backRequester,
        contentGeneration = listOf(state.worksLoading, state.works.size, state.otherWorks.size),
    )
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = TvSafeVertical, bottom = TvSafeVertical + 24.dp),
        verticalArrangement = Arrangement.spacedBy(26.dp),
    ) {
        item(key = "$route:header") {
            TvPersonHeader(
                state = state,
                personId = component.request.personId,
                route = route,
                focusMemory = focusMemory,
                backRequester = backRequester,
                onBack = component.onBack,
            )
        }
        item(key = "$route:library") {
            TvPersonLibraryWorks(state, component, route, focusMemory, backRequester)
        }
        if (state.otherWorks.isNotEmpty()) {
            item(key = "$route:tmdb") {
                TvMediaRow(
                    title = "TMDB 其他作品",
                    sectionKey = "$route:tmdb",
                    items = state.otherWorks.map { it.toTvCard { component.onOpenTmdbItem(it) } },
                    focusMemory = focusMemory,
                    navigationRequester = backRequester,
                    modifier = Modifier.padding(horizontal = TvSafeHorizontal - 8.dp),
                )
            }
        }
    }
}

/** One route per person, so a filmography opened from another keeps its own place — see tvFocusRoute. */
private fun tvPersonRoute(personId: String): String = "person-$personId"

@Composable
private fun TvPersonHeader(
    state: PersonPageState,
    personId: String,
    route: String,
    focusMemory: TvUiFocusMemory,
    backRequester: FocusRequester,
    onBack: () -> Unit,
) {
    // The age beside the birth date is a label, not a clock.
    val today = remember { currentIsoDate() }
    val server = state.server
    val portraits =
        listOfNotNull(
            server?.let { from ->
                state.serverImageTag?.let {
                    EmbyImages.primary(from.baseUrl, personId, it, maxHeight = 360, accessToken = from.accessToken)
                }
            },
            TmdbImages.poster(state.tmdbProfilePath, "h632"),
        )
    Column(
        Modifier
            // Whole while focus is on 返回 or 展开简介 — see TvFocusPivot.
            .tvKeepWholeInView()
            .padding(horizontal = TvSafeHorizontal),
    ) {
        TvActionButton(
            label = "返回",
            stableId = "$route:back",
            focusScope = "$route:header",
            focusMemory = focusMemory,
            onClick = onBack,
            modifier = Modifier.width(118.dp),
            icon = AppIcons.ChevronLeft,
            focusRequester = backRequester,
        )
        Spacer(Modifier.height(22.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(132.dp).clip(CircleShape).background(TvPlaceholder)) {
                portraits.firstOrNull()?.let { url ->
                    AsyncImage(
                        model = rememberTvImage(url),
                        // Silent: the name is written beside it.
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Column(Modifier.width(720.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(
                    state.displayName,
                    color = TvOnSurface,
                    fontSize = TvType.display,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val facts =
                    listOfNotNull(
                        state.tmdb?.knownForDepartment?.let(::departmentLabel),
                        personLifeLine(state.birthDate, state.deathDate, today),
                        personBirthPlaceLine(state.birthPlace),
                    )
                if (facts.isNotEmpty()) {
                    Text(facts.joinToString("  ·  "), color = TvOnSurfaceMuted, fontSize = TvType.caption)
                } else if (state.profileLoading) {
                    Text("正在读取资料", color = TvOnSurfaceMuted, fontSize = TvType.caption)
                }
            }
        }
        state.overview?.let { overview ->
            Spacer(Modifier.height(20.dp))
            TvPersonBiography(overview, state.overviewFromTmdb, route, focusMemory)
        }
    }
}

@Composable
private fun TvPersonBiography(
    text: String,
    fromTmdb: Boolean,
    route: String,
    focusMemory: TvUiFocusMemory,
) {
    var expanded by rememberSaveable(text) { mutableStateOf(false) }
    var overflowed by remember(text) { mutableStateOf(false) }
    Column(Modifier.width(980.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text,
            color = TvOnSurfaceMuted,
            fontSize = TvType.caption,
            lineHeight = TvType.readingLineHeight,
            maxLines = if (expanded) Int.MAX_VALUE else BIOGRAPHY_LINES,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!expanded) overflowed = it.hasVisualOverflow },
        )
        if (fromTmdb) {
            Text("简介来自 TMDB", color = TvOnSurfaceMuted.copy(alpha = 0.7f), fontSize = TvType.caption)
        }
        // A remote cannot tap text open: a key does it, there only when there is more to read.
        if (overflowed || expanded) {
            TvActionButton(
                label = if (expanded) "收起简介" else "展开简介",
                stableId = "$route:biography",
                focusScope = "$route:header",
                focusMemory = focusMemory,
                onClick = { expanded = !expanded },
                modifier = Modifier.width(150.dp),
            )
        }
    }
}

@Composable
private fun TvPersonLibraryWorks(
    state: PersonPageState,
    component: PersonComponent,
    route: String,
    focusMemory: TvUiFocusMemory,
    backRequester: FocusRequester,
) {
    val server = state.server
    val error = state.worksError
    when {
        state.serverMissing ->
            TvPersonNote("这台服务器已经不在列表里了")
        state.worksLoading ->
            Box(Modifier.fillMaxWidth().height(150.dp)) { TvLoadingState("正在读取库内作品") }
        error != null ->
            Row(
                Modifier.padding(horizontal = TvSafeHorizontal),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(error, color = TvOnSurfaceMuted, fontSize = TvType.caption)
                TvActionButton(
                    label = "重试",
                    stableId = "$route:retry",
                    focusScope = "$route:library",
                    focusMemory = focusMemory,
                    onClick = component::retry,
                    icon = AppIcons.Refresh,
                )
            }
        state.works.isEmpty() || server == null ->
            TvPersonNote("这台服务器上还没有 TA 的作品")
        else ->
            TvMediaRow(
                title = "库内作品 · ${state.works.size} 部",
                sectionKey = "$route:library",
                items = state.works.map { it.toTvCard(server) { component.onOpenItem(server.id, it.id) } },
                focusMemory = focusMemory,
                navigationRequester = backRequester,
                modifier = Modifier.padding(horizontal = TvSafeHorizontal - 8.dp),
            )
    }
}

@Composable
private fun TvPersonNote(text: String) {
    Column(Modifier.padding(horizontal = TvSafeHorizontal), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("库内作品", color = TvOnSurface, fontSize = TvType.section, fontWeight = FontWeight.Bold)
        Text(text, color = TvOnSurfaceMuted, fontSize = TvType.caption)
    }
}

/** A library title: marked 可播放, because this one plays and the TMDB row's do not. */
private fun MediaItem.toTvCard(
    server: SavedServer,
    onOpen: () -> Unit,
): TvMediaCardModel =
    TvMediaCardModel(
        stableId = "work:$id",
        title = title,
        subtitle = year?.toString(),
        imageUrl = EmbyImages.poster(server.baseUrl, this, accessToken = server.accessToken),
        serverId = server.id,
        profileId = server.userId,
        progress = playedPercentage?.div(100.0)?.toFloat(),
        badge = "可播放",
        onClick = onOpen,
    )

private fun TmdbItem.toTvCard(onOpen: () -> Unit): TvMediaCardModel =
    TvMediaCardModel(
        stableId = "tmdb:$mediaType:$id",
        title = title,
        subtitle = year,
        imageUrl = TmdbImages.poster(posterPath, "w342") ?: TmdbImages.backdrop(backdropPath, "w780"),
        onClick = onOpen,
    )

private const val BIOGRAPHY_LINES = 4
