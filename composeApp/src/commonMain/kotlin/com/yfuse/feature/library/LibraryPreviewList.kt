package com.yfuse.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.arkivanov.mvikotlin.extensions.coroutines.states
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.ThemeText
import com.yfuse.core.designsystem.flatGlass
import com.yfuse.core.network.EmbyImages

@Composable
internal fun LibraryPreviewList(
    component: LibraryGridComponent,
    selectedId: String,
    onSelect: (String) -> Unit,
) {
    val state by component.store.states.collectAsState(component.store.state)
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    val selectedIndex = state.items.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
    val list = rememberLazyListState(initialFirstVisibleItemIndex = selectedIndex)
    Column(
        Modifier
            .width(256.dp)
            .fillMaxHeight()
            .statusBarsPadding()
            .padding(horizontal = 12.dp),
    ) {
        ThemeText(
            component.title,
            color = palette.text,
            style = AppTypography.section.strong,
            modifier = Modifier.padding(12.dp),
        )
        LazyColumn(
            state = list,
            contentPadding = PaddingValues(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(state.items, key = { it.id }) { item ->
                val selected = item.id == selectedId
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(AppShapes.card)
                        .then(if (selected) Modifier.flatGlass(AppShapes.card, accent.container) else Modifier)
                        .selectable(selected = selected, onClick = { if (!selected) onSelect(item.id) })
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    AsyncImage(
                        model =
                            EmbyImages.poster(
                                component.serverBaseUrl,
                                item,
                                accessToken = component.serverAccessToken,
                            ),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.width(38.dp).height(56.dp).clip(AppShapes.thumb),
                    )
                    ThemeText(
                        item.title,
                        color = palette.text,
                        style = AppTypography.body.medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (state.canLoadMore && state.loadMoreError == null) {
                item(key = "load-more") {
                    LaunchedEffect(state.nextStartIndex) { component.store.accept(GridIntent.LoadMore) }
                }
            }
        }
    }
}
