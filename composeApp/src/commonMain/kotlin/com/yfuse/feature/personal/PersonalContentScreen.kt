package com.yfuse.feature.personal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yfuse.app.systemNavigationContentInset
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.DialogPresence
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.ErrorState
import com.yfuse.core.designsystem.FallbackImage
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.ItemAction
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.PageHint
import com.yfuse.core.designsystem.SwipeActionsRow
import com.yfuse.core.designsystem.Tips
import com.yfuse.core.designsystem.YfButton
import com.yfuse.core.designsystem.YfButtonTone
import com.yfuse.core.designsystem.YfFormField
import com.yfuse.core.designsystem.glass
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.model.MediaServerKind
import com.yfuse.core.model.SavedServer
import com.yfuse.core.network.EmbyImages
import com.yfuse.core.network.TmdbImages
import com.yfuse.core.personal.PersonalCollection
import com.yfuse.core.personal.PersonalEntry
import com.yfuse.core.personal.PersonalMediaRef
import com.yfuse.feature.profile.SettingsBackButton
import com.yfuse.core.designsystem.ThemeIcon as Icon
import com.yfuse.core.designsystem.ThemeText as Text

internal val PersonalContentTabs =
    listOf(PersonalCenterTab.WatchLater, PersonalCenterTab.Favorites, PersonalCenterTab.History)

/** The profile's own titles, separate from its settings and the servers' current lists. */
@Composable
internal fun PersonalContentScreen(
    state: PersonalCenterState,
    onIntent: (PersonalCenterIntent) -> Unit,
    servers: List<SavedServer>,
    onBack: () -> Unit,
    onOpenMedia: (PersonalMediaRef) -> Unit,
    initialTab: PersonalCenterTab,
    onSwipeable: (Boolean) -> Unit,
) {
    val library = state.library
    val profileId = library.activeProfile.id
    var tab by rememberSaveable(profileId, initialTab) { mutableStateOf(initialTab) }
    var query by rememberSaveable(profileId) { mutableStateOf("") }
    var searching by rememberSaveable(profileId) { mutableStateOf(false) }
    var tools by remember(profileId) { mutableStateOf(false) }
    var selectedEntry by remember(profileId) { mutableStateOf<PersonalEntry?>(null) }
    val collections = listOf(library.watchLater, library.favorites, library.history)
    val activeEntries = collections[PersonalContentTabs.indexOf(tab).coerceAtLeast(0)]
    val visible =
        activeEntries.filter {
            it.identity != state.pendingRemoval?.identity && it.media.title.contains(query.trim(), ignoreCase = true)
        }
    val counts = collections.map { entries -> entries.count { it.identity != state.pendingRemoval?.identity } }
    SideEffect { onSwipeable(visible.isNotEmpty()) }
    LazyColumn(
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(top = 14.dp, bottom = systemNavigationContentInset()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(key = "personal-heading") {
            PersonalContentHeader(
                profileName = library.activeProfile.name,
                onBack = onBack,
                searching = searching,
                onSearch = {
                    searching = !searching
                    if (!searching) query = ""
                },
                canImport = state.canImport && servers.isNotEmpty(),
                onTools = { tools = true },
            )
        }
        item(key = "personal-tabs") { PersonalContentFilters(tab, counts) { tab = it } }
        if (searching) {
            item(key = "personal-search") {
                YfFormField(
                    value = query,
                    onValueChange = { query = it },
                    label = "搜索${tab.label}",
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.pageHorizontal),
                )
            }
        }
        state.message?.let { notice -> item(key = "personal-message") { PersonalContentNotice(notice) } }
        state.failure?.let { failed ->
            item(key = "personal-error") {
                ErrorState(failed, { onIntent(PersonalCenterIntent.RetryFailure) }, Modifier.fillMaxWidth())
            }
        }
        library.error?.takeIf { it != state.failure }?.let { failed ->
            item(key = "personal-library-error") { PersonalContentNotice(failed, error = true) }
        }
        if (visible.isEmpty()) {
            item(key = "personal-empty") {
                PageHint(
                    text = personalContentEmptyText(tab, query),
                    modifier = Modifier.fillMaxWidth(),
                    icon = if (tab == PersonalCenterTab.History) AppIcons.Play else AppIcons.Bookmark,
                )
            }
        }
        items(visible, key = { it.identity }) { entry ->
            PersonalContentCard(
                entry = entry,
                servers = servers,
                onOpen = { onOpenMedia(entry.media) },
                onOptions = { selectedEntry = entry },
                onRemove = { onIntent(PersonalCenterIntent.RemoveEntry(entry)) },
            )
        }
    }
    PersonalContentTools(tools, state, servers, onIntent) { tools = false }
    PersonalEntryOptions(selectedEntry, onOpenMedia, onIntent) { selectedEntry = null }
}

@Composable
private fun PersonalContentHeader(
    profileName: String,
    onBack: () -> Unit,
    searching: Boolean,
    onSearch: () -> Unit,
    canImport: Boolean,
    onTools: () -> Unit,
) {
    val palette = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Dimens.pageHorizontal),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SettingsBackButton(onBack)
        Column(Modifier.weight(1f)) {
            Text("我的片单", style = AppTypography.section.strong, color = palette.text)
            Text(profileName, style = AppTypography.caption.regular, color = palette.sub2)
        }
        ContentIconButton(
            if (searching) AppIcons.Close else AppIcons.Search,
            if (searching) "收起搜索" else "搜索片单",
            onSearch,
        )
        if (canImport) ContentIconButton(AppIcons.More, "导入片单", onTools)
    }
}

@Composable
private fun PersonalContentFilters(
    selected: PersonalCenterTab,
    counts: List<Int>,
    onSelect: (PersonalCenterTab) -> Unit,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Dimens.pageHorizontal),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PersonalContentTabs.forEachIndexed { index, tab ->
            val label = if (tab == PersonalCenterTab.History) "观看历史" else tab.label
            val active = tab == selected
            Column(
                Modifier
                    .weight(1f)
                    .pressable(role = Role.Tab, onClickLabel = label, onClick = { onSelect(tab) })
                    .semantics {
                        contentDescription = label
                        this.selected = active
                    }.glass(
                        AppShapes.control,
                        fill = if (active) accent.container else palette.card2,
                        border = if (active) accent.border else palette.border,
                    ).padding(horizontal = 8.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    counts[index].toString(),
                    style = AppTypography.section.strong,
                    color = if (active) accent.accent else palette.text,
                )
                Text(
                    label,
                    style = AppTypography.caption.regular,
                    color = if (active) accent.accent else palette.sub2,
                    maxLines = 2,
                )
            }
        }
    }
}

@Composable
private fun PersonalContentCard(
    entry: PersonalEntry,
    servers: List<SavedServer>,
    onOpen: () -> Unit,
    onOptions: () -> Unit,
    onRemove: () -> Unit,
) {
    val palette = LocalPalette.current
    SwipeActionsRow(
        modifier = Modifier.padding(horizontal = Dimens.pageHorizontal),
        tipId = Tips.SWIPE_ROW_HISTORY,
        trailing =
            ItemAction(
                "移除",
                AppIcons.Close,
                destructive = true,
                undoable = true,
                id = "personal.remove",
                onSelect = onRemove,
            ),
    ) { actions ->
        Row(
            Modifier.fillMaxWidth().glass(AppShapes.card, palette.card2, palette.border).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                actions
                    .weight(
                        1f,
                    ).pressable(
                        onClickLabel = "查看作品",
                        onLongClick = onOptions,
                        onLongClickLabel = "作品操作",
                        onClick = onOpen,
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                PersonalContentPoster(entry.media, servers)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        entry.media.title,
                        style = AppTypography.body.strong,
                        color = palette.text,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val metadata =
                        listOfNotNull(
                            entry.media.year?.toString(),
                            personalMediaTypeLabel(entry.media.mediaType),
                        ).joinToString(" · ")
                    if (metadata.isNotEmpty()) {
                        Text(
                            metadata,
                            style = AppTypography.caption.regular,
                            color = palette.sub2,
                        )
                    }
                    if (entry.collection == PersonalCollection.History) PersonalHistoryProgress(entry)
                }
            }
            // A focused remote or keyboard can reach removal without performing a swipe.
            ContentIconButton(AppIcons.More, "${entry.media.title}的操作", onOptions)
        }
    }
}

@Composable
private fun PersonalContentPoster(
    media: PersonalMediaRef,
    servers: List<SavedServer>,
) {
    val palette = LocalPalette.current
    val urls = remember(media, servers) { personalPosterUrls(media, servers) }
    Box(
        Modifier.size(width = 64.dp, height = 96.dp).clip(AppShapes.card).background(palette.card3),
        contentAlignment = Alignment.Center,
    ) {
        Icon(AppIcons.Bookmark, null, tint = palette.sub2, modifier = Modifier.size(24.dp))
        FallbackImage(urls, null, Modifier.fillMaxSize())
    }
}

private fun personalPosterUrls(
    media: PersonalMediaRef,
    servers: List<SavedServer>,
): List<String?> {
    val server = servers.firstOrNull { it.id == media.serverId }
    // Personal references retain item identity, not Plex's artwork path; never request an
    // Emby endpoint on a Plex server just to fill a poster slot.
    val serverPoster =
        server?.takeUnless { it.kind == MediaServerKind.Plex }?.let {
            EmbyImages.primary(
                it.baseUrl,
                media.serverItemId.orEmpty(),
                tag = null,
                maxHeight = 220,
                accessToken = it.accessToken,
            )
        }
    return listOf(serverPoster, TmdbImages.poster(media.posterPath, "w185"), TmdbImages.media(media.posterPath, "w185"))
}

private fun personalMediaTypeLabel(type: String): String? =
    when (type.lowercase()) {
        "movie" -> "电影"
        "series", "tv" -> "剧集"
        "episode" -> "单集"
        "season" -> "季"
        else -> null
    }

@Composable
private fun PersonalHistoryProgress(entry: PersonalEntry) {
    val palette = LocalPalette.current
    val position = entry.positionMs.coerceAtLeast(0L)
    val minutes = position / 60_000
    val seconds = (position / 1_000 % 60).toString().padStart(2, '0')
    Text(
        if (entry.completed) "已看完" else "看到 $minutes:$seconds",
        style = AppTypography.caption.regular,
        color = palette.sub,
    )
    if (entry.durationMs > 0L) {
        val progress = if (entry.completed) 1f else (position.toDouble() / entry.durationMs).toFloat().coerceIn(0f, 1f)
        Box(
            Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(AppShapes.track)
                .background(palette.card3),
        ) {
            Box(Modifier.fillMaxWidth(progress).height(3.dp).background(LocalAccentColors.current.accent))
        }
    }
}

@Composable
private fun ContentIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Box(
        Modifier.size(48.dp).pressable(onClickLabel = label, label = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = LocalPalette.current.text, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun PersonalContentNotice(
    notice: String,
    error: Boolean = false,
) {
    Text(
        notice,
        style = AppTypography.caption.regular,
        color = if (error) LocalPalette.current.error else LocalPalette.current.sub2,
        modifier = Modifier.padding(horizontal = Dimens.pageHorizontal),
    )
}

private fun personalContentEmptyText(
    tab: PersonalCenterTab,
    query: String,
): String =
    when {
        query.isNotBlank() -> "没有找到「${query.trim()}」，试试其他片名。"
        tab == PersonalCenterTab.History -> "还没有观看记录。播放后，可以在这里查看已看的作品。"
        tab == PersonalCenterTab.Favorites -> "还没有收藏。在作品详情中加入收藏，喜欢的作品会留在这里。"
        else -> "还没有想看的作品。在作品详情中加入想看，留待下次观看。"
    }

@Composable
private fun PersonalContentTools(
    open: Boolean,
    state: PersonalCenterState,
    servers: List<SavedServer>,
    onIntent: (PersonalCenterIntent) -> Unit,
    onDismiss: () -> Unit,
) {
    DialogPresence(open) { shown ->
        if (shown) {
            GlassDialog(onDismiss) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("片单工具", style = AppTypography.section.strong)
                    Text("把媒体服务器中的收藏与想看加入当前片单。", style = AppTypography.body.regular)
                    YfButton(
                        label = "从媒体服务器导入",
                        loading = state.busy,
                        enabled = state.canImport && servers.isNotEmpty(),
                        onClick = {
                            onIntent(PersonalCenterIntent.ImportFromServers(servers))
                            onDismiss()
                        },
                    )
                    YfButton("关闭", onDismiss, tone = YfButtonTone.Secondary)
                }
            }
        }
    }
}

@Composable
private fun PersonalEntryOptions(
    entry: PersonalEntry?,
    onOpenMedia: (PersonalMediaRef) -> Unit,
    onIntent: (PersonalCenterIntent) -> Unit,
    onDismiss: () -> Unit,
) {
    DialogPresence(entry) { selected ->
        if (selected != null) {
            GlassDialog(onDismiss) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(selected.media.title, style = AppTypography.section.strong)
                    YfButton("查看作品", {
                        onDismiss()
                        onOpenMedia(selected.media)
                    })
                    YfButton("移除${if (selected.collection == PersonalCollection.History) "观看记录" else "作品"}", {
                        onDismiss()
                        onIntent(PersonalCenterIntent.RemoveEntry(selected))
                    }, tone = YfButtonTone.Destructive)
                    YfButton("取消", onDismiss, tone = YfButtonTone.Secondary)
                }
            }
        }
    }
}
