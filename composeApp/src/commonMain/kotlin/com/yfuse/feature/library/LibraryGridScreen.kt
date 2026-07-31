package com.yfuse.feature.library

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.arkivanov.mvikotlin.extensions.coroutines.states
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.ErrorState
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.OverlayHeader
import com.yfuse.core.designsystem.OverlayOptionRow
import com.yfuse.core.designsystem.StatusBarIconStyle
import com.yfuse.core.designsystem.glass
import com.yfuse.core.designsystem.mr
import com.yfuse.core.designsystem.sc

/**
 * 「查看更多」grid. The prototype expands a category in place, so this page reuses
 * that layout: a 3-up grid of 150px posters with title/year below.
 */
@Composable
fun LibraryGridScreen(component: LibraryGridComponent) {
    val state by component.store.states.collectAsState(component.store.state)
    val baseUrl = component.serverBaseUrl
    val accessToken = component.serverAccessToken
    val palette = LocalPalette.current
    StatusBarIconStyle(darkIcons = !palette.isDark)
    var sort by remember { mutableStateOf("最近添加") }
    var sortOpen by remember { mutableStateOf(false) }
    val sortedItems = remember(state.items, sort) {
        when (sort) {
            "名称" -> state.items.sortedBy { it.title }
            "年份" -> state.items.sortedByDescending { it.year ?: 0 }
            else -> state.items
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dimens.pageHorizontal)
                    .padding(top = 12.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(34.dp)
                        .glass(GlassShapes.chip)
                        .clickable(onClick = component.onBack),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        AppIcons.ChevronLeft,
                        contentDescription = "返回",
                        tint = palette.text,
                        modifier = Modifier.size(15.dp),
                    )
                }
                Text(
                    component.title,
                    style = sc(22f, 800),
                    color = palette.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text("${state.items.size} 部", style = mr(11f, 500), color = palette.sub2)
                Row(
                    Modifier
                        .height(34.dp)
                        .glass(
                            RoundedCornerShape(17.dp),
                            palette.glassStrong,
                            palette.tabbarBorder,
                        )
                        .clickable { sortOpen = true }
                        .padding(horizontal = 13.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(AppIcons.Menu, null, tint = palette.sub, modifier = Modifier.size(13.dp))
                    Text(sort, style = sc(11.5f, 600), color = palette.text)
                }
            }

            Box(Modifier.fillMaxSize()) {
                when {
                    state.loading && state.items.isEmpty() ->
                        CircularProgressIndicator(Modifier.align(Alignment.Center))

                    state.error != null && state.items.isEmpty() -> ErrorState(
                        message = state.error!!,
                        onRetry = { component.store.accept(GridIntent.Retry) },
                        modifier = Modifier.align(Alignment.Center),
                    )

                    else -> LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        contentPadding = PaddingValues(
                            start = Dimens.pageHorizontal,
                            end = Dimens.pageHorizontal,
                            bottom = Dimens.contentBottom,
                        ),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(sortedItems, key = { it.id }) { item ->
                            PosterCard(
                                baseUrl = baseUrl,
                                accessToken = accessToken,
                                item = item,
                                showProgress = false,
                                onClick = { component.onOpenItem(item.id) },
                            )
                        }
                    }
                }
            }
        }

        // 排序 was a Material [DropdownMenu] hung off the chip — the last anchored menu in the
        // app, and the one shape the overlay system exists to replace. Centred like every
        // other overlay outside the player now; see [com.yfuse.core.designsystem.GlassDialog].
        if (sortOpen) {
            GlassDialog(onDismiss = { sortOpen = false }) {
                OverlayHeader(title = "排序", onClose = { sortOpen = false })
                listOf("最近添加", "名称", "年份").forEach { option ->
                    OverlayOptionRow(
                        label = option,
                        selected = sort == option,
                        onClick = {
                            sort = option
                            sortOpen = false
                        },
                    )
                }
            }
        }
    }
}
