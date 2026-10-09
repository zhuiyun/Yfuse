package com.yfuse.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.DialogAnimation
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.ItemAction
import com.yfuse.core.designsystem.LiftMenu
import com.yfuse.core.designsystem.overlayAction
import com.yfuse.core.designsystem.overlayActionBeforeExit
import com.yfuse.core.designsystem.overlayDismiss
import com.yfuse.tv.focus.AndroidRemoteKeyMapper
import com.yfuse.tv.focus.RemoteIntent
import com.yfuse.tv.focus.RemotePhysicalKey
import com.yfuse.tv.focus.requestFocusWhenAttached
import com.yfuse.tv.focus.tvFocusScope
import com.yfuse.tv.focus.tvIgnoreOpeningHold

/** The panel's focus scope — a route of its own, so its rows never become a page's saved focus. */
private const val TV_QUICK_SCOPE = "tv-quick"

private val TvQuickActionsWidth = 380.dp
private val TvQuickActionRowHeight = 52.dp

/** Square where it meets the right edge of the screen, rounded where it faces the page. */
private val TvQuickActionsShape = RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp)

/**
 * The rows the panel offers for [menu]: its own groups, then 查看详情 when the title opens. On
 * the phone that is releasing on the preview card, which a remote has no way to point at.
 */
internal fun tvQuickActionSections(menu: LiftMenu): List<List<ItemAction>> {
    val open = menu.onOpen ?: return menu.sections
    val detail =
        ItemAction(
            label = "查看详情",
            icon = AppIcons.Info,
            leavesPage = true,
            id = "detail",
            onSelect = open,
        )
    return menu.sections + listOf(listOf(detail))
}

/**
 * What opens a card's panel: holding 确定, or the remote's 菜单 key — the one key a television
 * remote has for "more about this", and on many of them the only way to a card's actions that
 * does not depend on how long a press was held.
 */
internal fun RemoteIntent.opensTvQuickActions(): Boolean =
    this is RemoteIntent.OpenContextMenu || this is RemoteIntent.Menu

/**
 * This menu without its 查看详情, for a card whose own press already does what opening the title
 * would — an episode's picks it. The phone's 按住拖看 frames go too: a remote has nothing to scrub
 * them with.
 */
internal fun LiftMenu.withoutOpening(): LiftMenu =
    LiftMenu(
        title = title,
        meta = meta,
        artworkUrls = artworkUrls,
        backdropUrls = backdropUrls,
        progress = progress,
        progressLabel = progressLabel,
        onOpen = null,
        anchored = anchored,
        sections = sections,
    )

/**
 * 长按面板 — holding 确定 on a content card for [com.yfuse.tv.focus.REMOTE_LONG_PRESS_MILLIS], or
 * pressing 菜单 on it: that title's [ItemAction]s, the rows the phone's 浮起菜单 offers, in a panel at
 * the right edge of the screen. The first row takes focus; 返回, left towards the card, or 菜单
 * again puts it away.
 *
 * An action that takes the screen — 播放, 查看详情 — goes at once while the panel leaves. One that
 * changes the title in place waits until the panel has gone and the card has focus again, so the
 * change lands where the person is looking. In case it takes the card off its shelf (a title
 * marked watched leaves 继续观看), a restore is left pending on the card's [route], and the shelf
 * puts focus on the nearest card instead of dropping it. It is the card's own restore
 * ([restoreOwner]), because most changes leave the card where it is: the card ends it once focus
 * moves on — see [TvMediaCard]. One nobody could end pulled focus back off the rail long after.
 */
@Composable
internal fun TvQuickActionsPanel(
    menu: LiftMenu,
    focusMemory: TvUiFocusMemory,
    route: String,
    restoreOwner: Any,
    onDismiss: () -> Unit,
) {
    val sections = remember(menu) { tvQuickActionSections(menu) }
    val firstRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstRequester.requestFocusWhenAttached() }

    GlassDialog(
        onDismiss = onDismiss,
        scrollable = false,
        contentPadding = 0.dp,
        alignment = Alignment.CenterEnd,
        windowPadding = PaddingValues(0.dp),
        shape = TvQuickActionsShape,
        maxWidth = TvQuickActionsWidth,
        dragHandle = false,
        dragToDismiss = false,
        // Pinned to the edge, it rises in place whatever style the other dialogs use.
        animation = DialogAnimation.Lift,
    ) {
        val dismiss = overlayDismiss(onDismiss)
        Column(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth()
                .tvIgnoreOpeningHold()
                .onPreviewKeyEvent { event ->
                    val native = event.nativeKeyEvent
                    val key = AndroidRemoteKeyMapper.physicalKey(native.keyCode)
                    // 菜单 only on a fresh press: a held 菜单 that opened the panel repeats into it.
                    val closes =
                        key == RemotePhysicalKey.DirectionLeft ||
                            (key == RemotePhysicalKey.Menu && native.repeatCount == 0)
                    if (event.type == KeyEventType.KeyDown && closes) {
                        dismiss()
                        true
                    } else {
                        false
                    }
                }.tvFocusScope(trapFocus = true)
                .padding(start = 28.dp, end = TvSafeHorizontal, top = TvSafeVertical + 24.dp, bottom = TvSafeVertical),
        ) {
            TvQuickActionsHeader(menu)
            Spacer(Modifier.height(22.dp))
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                sections.forEachIndexed { sectionIndex, section ->
                    if (sectionIndex > 0) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 6.dp)
                                .height(1.dp)
                                .background(TvHairline),
                        )
                    }
                    section.forEachIndexed { index, action ->
                        TvQuickActionRow(
                            action = action,
                            focusMemory = focusMemory,
                            route = route,
                            restoreOwner = restoreOwner,
                            onDismiss = onDismiss,
                            focusRequester = if (sectionIndex == 0 && index == 0) firstRequester else null,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TvQuickActionsHeader(menu: LiftMenu) {
    Text(
        menu.title,
        color = TvOnSurface,
        fontSize = TvType.section,
        fontWeight = FontWeight.ExtraBold,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
    menu.meta?.let { meta ->
        Spacer(Modifier.height(6.dp))
        Text(meta, color = TvOnSurfaceMuted, fontSize = TvType.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    menu.progress?.let { progress ->
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color.White.copy(alpha = 0.18f)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                    .height(4.dp)
                    .background(TvAccent),
            )
        }
    }
    menu.progressLabel?.let { label ->
        Spacer(Modifier.height(6.dp))
        Text(label, color = TvOnSurfaceMuted, fontSize = TvType.caption, maxLines = 1)
    }
}

/** One [ItemAction] as a remote-sized row: white plate and black ink when focused, as the rail. */
@Composable
private fun TvQuickActionRow(
    action: ItemAction,
    focusMemory: TvUiFocusMemory,
    route: String,
    restoreOwner: Any,
    onDismiss: () -> Unit,
    focusRequester: FocusRequester?,
) {
    val run =
        if (action.leavesPage) {
            // Its result takes the screen anyway: go at once and let the panel leave alongside.
            overlayActionBeforeExit {
                onDismiss()
                action.onSelect()
            }
        } else {
            overlayAction {
                onDismiss()
                focusMemory.beginRestore(route, owner = restoreOwner)
                action.onSelect()
            }
        }
    TvFocusableSurface(
        stableId = "$TV_QUICK_SCOPE:${action.id}",
        focusScope = TV_QUICK_SCOPE,
        focusMemory = focusMemory,
        onClick = run,
        contentDescription = listOfNotNull(action.label, action.detail).joinToString("，"),
        modifier = Modifier.fillMaxWidth().height(TvQuickActionRowHeight),
        focusRequester = focusRequester,
        shape = RoundedCornerShape(12.dp),
        scaleWhenFocused = TvFocusMotion.ROW_SCALE,
    ) {
        val focus = LocalTvFocusAmount.current
        val restInk = if (action.destructive) TvDanger else TvOnSurface
        Row(
            Modifier
                .fillMaxSize()
                .drawBehind { drawRect(Color.White.copy(alpha = focus.value.coerceIn(0f, 1f))) }
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            action.icon?.let { icon ->
                TvFocusIcon(icon = icon, rest = restInk, focused = Color.Black, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(14.dp))
            }
            TvFocusText(
                text = action.label,
                rest = restInk,
                focused = Color.Black,
                fontSize = TvType.body,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            action.detail?.let { detail ->
                Spacer(Modifier.width(12.dp))
                TvFocusText(
                    text = detail,
                    rest = TvOnSurfaceMuted,
                    focused = Color.Black.copy(alpha = 0.62f),
                    fontSize = TvType.caption,
                    fontWeight = FontWeight.Normal,
                )
            }
        }
    }
}
