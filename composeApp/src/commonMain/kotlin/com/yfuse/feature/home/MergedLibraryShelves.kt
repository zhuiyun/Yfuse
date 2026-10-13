package com.yfuse.feature.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.arkivanov.mvikotlin.extensions.coroutines.states
import com.yfuse.core.data.HomeShelfLayout
import com.yfuse.core.designsystem.ActionToast
import com.yfuse.core.designsystem.ErrorState
import com.yfuse.core.designsystem.LiftMenu
import com.yfuse.core.designsystem.LocalRouteVisible
import com.yfuse.core.designsystem.OverlayPage
import com.yfuse.core.designsystem.SectionHeader
import com.yfuse.core.designsystem.ToastAction
import com.yfuse.core.designsystem.ZoomBackAnchor
import com.yfuse.core.designsystem.zoomBackAnchor
import com.yfuse.core.util.rememberPosterCardSharer
import kotlinx.coroutines.flow.MutableStateFlow

internal enum class MergedLibraryShelf(
    val title: String,
    val id: String,
) {
    Resume("继续观看", HOME_SHELF_CONTINUE),
    NextUp("下一集", HOME_SHELF_NEXT_UP),
    Recent("最近入库", "recent"),
    Favorites("我的收藏", HOME_SHELF_FAVORITES),
    Calendar("追剧日历", HOME_SHELF_CALENDAR),
}

internal class MergedLibraryShelves(
    val component: HomeComponent,
    val state: State<HomeState>,
    val calendar: State<HomeCalendarState>,
    val layout: State<HomeShelfLayout>,
    private val share: (HomeResumeEntry) -> Unit,
) {
    var opened by mutableStateOf<MergedLibraryShelf?>(null)
    var editing by mutableStateOf(false)
    var source by mutableStateOf<ZoomBackAnchor?>(null)
    var editingLayout by mutableStateOf(false)
    private val anchors = mutableMapOf<MergedLibraryShelf, ZoomBackAnchor>()

    val visibleShelves: List<MergedLibraryShelf>
        get() =
            layout.value.visible(MergedLibraryShelf.entries.map { it.id }).mapNotNull { id ->
                MergedLibraryShelf.entries.firstOrNull { it.id == id }
            }

    fun hasContent(shelf: MergedLibraryShelf): Boolean =
        if (shelf == MergedLibraryShelf.Calendar) {
            calendar.value.days.isNotEmpty() || calendar.value.error != null
        } else {
            entries(shelf).isNotEmpty()
        }

    fun anchor(shelf: MergedLibraryShelf): ZoomBackAnchor = anchors.getOrPut(shelf) { ZoomBackAnchor() }

    fun entries(shelf: MergedLibraryShelf): List<HomeResumeEntry> =
        when (shelf) {
            MergedLibraryShelf.Resume -> state.value.resume
            MergedLibraryShelf.NextUp -> homeNextUpShelf(state.value.nextUp, state.value.resume)
            MergedLibraryShelf.Recent -> state.value.recentAdded
            MergedLibraryShelf.Favorites -> state.value.favorites
            MergedLibraryShelf.Calendar -> emptyList()
        }

    fun open(
        shelf: MergedLibraryShelf,
        selecting: Boolean = false,
    ) {
        editing = selecting
        source = anchor(shelf)
        opened = shelf
    }

    fun accept(intent: HomeIntent) {
        component.store.accept(intent)
        if (intent is HomeIntent.OpenResume || intent is HomeIntent.PlayEntry) opened = null
    }

    fun play(entry: HomeResumeEntry) = accept(HomeIntent.PlayEntry(entry))

    fun detail(entry: HomeResumeEntry) = accept(HomeIntent.OpenResume(entry))

    fun menu(
        entry: HomeResumeEntry,
        shelf: MergedLibraryShelf,
    ): LiftMenu =
        entry.homeLiftMenu(
            onIntent = ::accept,
            inResume = shelf == MergedLibraryShelf.Resume,
            playsOnTap = shelf == MergedLibraryShelf.Resume || shelf == MergedLibraryShelf.NextUp,
            undoWatched = true,
            onShare = { share(entry) },
        )
}

@Composable
internal fun rememberMergedLibraryShelves(component: HomeComponent?): MergedLibraryShelves? {
    if (component == null) return null
    val state = component.store.states.collectAsState(component.store.state)
    val calendar = component.calendar.collectAsState()
    val preferences = component.shelves
    val layout = remember(preferences) { preferences?.layout ?: MutableStateFlow(HomeShelfLayout()) }.collectAsState()
    val sharer = rememberPosterCardSharer()
    val visible = LocalRouteVisible.current
    LaunchedEffect(component, visible) {
        if (visible) {
            component.store.accept(HomeIntent.RefreshLibrary)
            component.refreshCalendar()
        }
    }
    return remember(component) {
        MergedLibraryShelves(component, state, calendar, layout) { entry -> sharer.sharePosterCard(entry.shareCard()) }
    }
}

@Composable
internal fun MergedLibraryEditAction(hub: MergedLibraryShelves) {
    if (hub.component.shelves != null) {
        SectionHeader("观看栏目", actionLabel = "调整", onAction = { hub.editingLayout = true })
    }
}

@Composable
internal fun MergedLibraryRail(
    hub: MergedLibraryShelves,
    shelf: MergedLibraryShelf,
) {
    val entries = hub.entries(shelf)
    if (shelf == MergedLibraryShelf.Calendar) {
        val previews =
            remember(hub.calendar.value.days, hub.state.value) {
                homeCalendarPreviews(hub.calendar.value.days, hub.state.value)
            }
        if (previews.isNotEmpty()) {
            HomeCalendarShelf(
                items = previews,
                onSeeAll = hub.component.onOpenCalendar,
                onClick = { hub.component.openCalendarEntry(it.entry) },
                liftMenu = { preview ->
                    preview.homeLiftMenu(
                        onOpen = { hub.component.openCalendarEntry(preview.entry) },
                        onOpenCalendar = hub.component.onOpenCalendar,
                    )
                },
            )
        } else if (hub.calendar.value.error != null) {
            ErrorState(
                message = hub.calendar.value.error!!,
                onRetry = { hub.component.refreshCalendar(forceRefresh = true) },
            )
        }
        return
    }
    if (entries.isEmpty()) return
    Box(Modifier.zoomBackAnchor(hub.anchor(shelf))) {
        if (shelf == MergedLibraryShelf.Resume || shelf == MergedLibraryShelf.NextUp) {
            ContinueWatching(
                items = entries,
                title = shelf.title,
                onSeeAll = { hub.open(shelf) },
                onEdit = if (shelf == MergedLibraryShelf.Resume) ({ hub.open(shelf, selecting = true) }) else null,
                onPlay = hub::play,
                onOpen = hub::detail,
                liftMenu = { hub.menu(it, shelf) },
            )
        } else {
            LibraryMediaShelf(
                title = shelf.title,
                items = entries,
                onSeeAll = { hub.open(shelf) },
                onClick = hub::detail,
                liftMenu = { hub.menu(it, shelf) },
            )
        }
    }
}

@Composable
internal fun BoxScope.MergedLibraryOverlays(hub: MergedLibraryShelves) {
    val state = hub.state.value
    val openedEmpty =
        hub.opened
            ?.takeIf { it == MergedLibraryShelf.Resume || it == MergedLibraryShelf.NextUp }
            ?.let { hub.entries(it).isEmpty() } == true
    LaunchedEffect(openedEmpty) { if (openedEmpty) hub.opened = null }
    OverlayPage(value = hub.opened, onBack = { hub.opened = null }, source = hub.source) { shelf ->
        val entries = hub.entries(shelf)
        val playsOnTap = shelf == MergedLibraryShelf.Resume || shelf == MergedLibraryShelf.NextUp
        LibraryRowPage(
            title = shelf.title,
            caption =
                libraryRowPageCaption(
                    source = "观看中心",
                    shown = entries.size,
                    total = if (shelf == MergedLibraryShelf.Favorites) state.favoritesTotal else entries.size,
                ),
            entries = entries,
            onOpen = if (playsOnTap) hub::play else hub::detail,
            liftMenu = { hub.menu(it, shelf) },
            onDismiss = { hub.opened = null },
            editing = hub.editing,
            onEditingChange = { hub.editing = it },
            selectionActions =
                if (shelf == MergedLibraryShelf.Resume) {
                    { selected ->
                        resumeSelectionActions(selected) { intent ->
                            hub.accept(intent)
                            hub.editing = false
                        }
                    }
                } else {
                    null
                },
        )
    }
    ActionToast(
        message = state.actionMessage,
        onDismiss = { hub.accept(HomeIntent.DismissMessage) },
        action = state.resumeUndoKey?.let { key -> ToastAction("撤销") { hub.accept(HomeIntent.UndoResumeChange(key)) } },
    )
    val preferences = hub.component.shelves
    if (hub.editingLayout && preferences != null) {
        val ids = MergedLibraryShelf.entries.map { it.id }
        val layout = hub.layout.value
        HomeShelfEditorSheet(
            title = "调整观看栏目",
            options =
                layout.arranged(ids).map { id ->
                    HomeShelfOption(id, MergedLibraryShelf.entries.first { it.id == id }.title)
                },
            hidden = layout.hidden,
            onMove = { id, to -> preferences.update(layout.moved(ids, id, to)) },
            onToggle = { id, visible -> preferences.update(layout.withVisible(id, visible)) },
            onReset = {
                preferences.update(
                    layout.copy(
                        order = layout.order.filterNot { it in ids },
                        hidden =
                            layout.hidden - ids.toSet(),
                    ),
                )
            },
            onDismiss = { hub.editingLayout = false },
        )
    }
}
