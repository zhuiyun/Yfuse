package com.yfuse.feature.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.arkivanov.decompose.extensions.compose.stack.Children
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import com.arkivanov.mvikotlin.extensions.coroutines.states
import com.yfuse.app.TabBarInset
import com.yfuse.app.hideBottomBarOnScroll
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.Brand
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.Poster
import com.yfuse.core.designsystem.Shadows
import com.yfuse.core.designsystem.glass
import com.yfuse.core.designsystem.mr
import com.yfuse.core.designsystem.sc
import com.yfuse.core.designsystem.shadow
import com.yfuse.core.model.MediaItem
import com.yfuse.core.network.EmbyImages
import com.yfuse.feature.detail.DetailScreen
import com.yfuse.feature.player.PlayerScreen

@Composable
fun SearchScreen(component: SearchComponent) {
    val focusRequest by component.focusRequest.subscribeAsState()
    Children(stack = component.stack) { child ->
        when (val instance = child.instance) {
            is SearchComponent.Child.Home -> SearchHomeScreen(instance.component, focusRequest)
            is SearchComponent.Child.Detail -> DetailScreen(instance.component)
            is SearchComponent.Child.Player -> PlayerScreen(instance.component)
        }
    }
}

/** 搜索 — `padding:52px 18px 100px; gap:20px`. */
@Composable
private fun SearchHomeScreen(component: SearchHomeComponent, focusRequest: Int) {
    val state by component.store.states.collectAsState(component.store.state)
    val palette = LocalPalette.current
    val store = component.store
    val fieldFocusRequester = androidx.compose.runtime.remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(focusRequest) {
        if (focusRequest > 0) {
            fieldFocusRequester.requestFocus()
            keyboard?.show()
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().statusBarsPadding().hideBottomBarOnScroll(),
            contentPadding = PaddingValues(top = Dimens.contentTop, bottom = TabBarInset),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item {
                SearchField(
                    query = state.query,
                    onQueryChange = { store.accept(SearchIntent.QueryChanged(it)) },
                    onSubmit = { store.accept(SearchIntent.Submit) },
                    onClear = { store.accept(SearchIntent.Clear) },
                    focusRequester = fieldFocusRequester,
                )
            }

            // Nothing typed yet: the chip row alone, no empty results heading.
            if (state.hasSearched || state.error != null) item {
                Column(Modifier.padding(horizontal = Dimens.pageHorizontal)) {
                    Text(
                        "搜索结果",
                        style = sc(13f, 700),
                        color = palette.text,
                        modifier = Modifier.padding(bottom = 10.dp),
                    )
                    when {
                        state.error != null -> Column(
                            Modifier.fillMaxWidth().padding(vertical = 20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                state.error!!,
                                style = mr(12f, 400),
                                color = palette.hint,
                                textAlign = TextAlign.Center,
                            )
                            TextButton(onClick = { store.accept(SearchIntent.Retry) }) {
                                Text("重试", style = sc(12.5f, 700), color = Brand.Primary)
                            }
                        }

                        // 没有找到相关内容 — `400 12px Manrope`, `--pg-hint`, `padding:20px 0`.
                        state.hasSearched && state.items.isEmpty() && !state.loading -> Text(
                            "没有找到相关内容",
                            style = mr(12f, 400),
                            color = palette.hint,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
                        )

                        else -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            state.items.forEach { item ->
                                ResultRow(
                                    baseUrl = component.serverBaseUrl,
                                    item = item,
                                    onClick = { component.onOpenItem(item.id) },
                                )
                            }
                        }
                    }
                }
            }

            if (state.recent.isNotEmpty()) {
                item {
                    RecentSearches(
                        terms = state.recent,
                        onSelect = { store.accept(SearchIntent.QueryChanged(it)) },
                        onForget = { store.accept(SearchIntent.ForgetRecent(it)) },
                        onClearAll = { store.accept(SearchIntent.ClearRecent) },
                    )
                }
            }
        }

        if (state.loading) {
            CircularProgressIndicator(
                Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 110.dp),
            )
        }
    }
}

/**
 * `--pg-card3` fill, `1px solid rgba(61,100,201,.4)`, `radius:20px`,
 * `padding:11px 16px`, `gap:8px`, `0 6px 18px rgba(90,120,180,.15)`.
 */
@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onClear: () -> Unit,
    focusRequester: FocusRequester,
) {
    val palette = LocalPalette.current
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.pageHorizontal)
            .shadow(Shadows.searchBarFocused, shape)
            .glass(shape, palette.card3, Brand.Primary.copy(alpha = 0.4f))
            .padding(horizontal = 16.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AppIcons.Search, null, tint = Brand.Primary, modifier = Modifier.size(15.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) {
                Text("搜索电影、剧集、演员", style = mr(13f, 400), color = palette.sub2)
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = mr(13f, 400).copy(color = palette.text),
                cursorBrush = SolidColor(Brand.Primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            )
        }
        if (query.isNotEmpty()) {
            Icon(
                AppIcons.Close,
                contentDescription = "清空搜索",
                tint = palette.sub2,
                modifier = Modifier.size(13.dp).clickable(onClick = onClear),
            )
        }
    }
}

/**
 * Result row — `gap:11px`, `--pg-card` over 1px `--pg-border`, `radius:16px`,
 * `padding:8px`; title `700 13px`, meta `400 10.5px Manrope`. The poster is larger
 * than the prototype's 56×80 and carries a synopsis, per the brief.
 */
@Composable
private fun ResultRow(baseUrl: String, item: MediaItem, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .glass(GlassShapes.card)
            .clickable(onClick = onClick)
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Poster(
            url = EmbyImages.poster(baseUrl, item, maxHeight = 360),
            modifier = Modifier.width(84.dp).height(120.dp),
        )
        Column(Modifier.weight(1f).padding(vertical = 2.dp)) {
            Text(
                item.title,
                style = sc(13f, 700),
                color = palette.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.subtitle != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    item.subtitle,
                    style = mr(10.5f, 400),
                    color = palette.sub2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!item.overview.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    item.overview,
                    style = sc(11.5f, 400, lineHeight = 11.5f * 1.6f),
                    color = palette.body,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The prototype's 热门搜索 chip row, filled with this device's search history —
 * there is no trending endpoint behind this app. Styling is the annotated one:
 * `--pg-card2` over 1px `--pg-border`, `radius:14px`, `padding:7px 13px`,
 * `500 11px Manrope`, `--pg-body`, `gap:8px`. Long-press a chip to forget it.
 */
@OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)
@Composable
private fun RecentSearches(
    terms: List<String>,
    onSelect: (String) -> Unit,
    onForget: (String) -> Unit,
    onClearAll: () -> Unit,
) {
    val palette = LocalPalette.current
    Column(Modifier.padding(horizontal = Dimens.pageHorizontal)) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("搜索记录", style = sc(13f, 700), color = palette.text)
            Text(
                "清空",
                style = mr(11f, 600),
                color = palette.sub2,
                modifier = Modifier.clickable(onClick = onClearAll),
            )
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            terms.forEach { term ->
                Text(
                    term,
                    style = mr(11f, 500),
                    color = palette.body,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .glass(GlassShapes.chip, palette.card2)
                        .combinedClickable(
                            onClick = { onSelect(term) },
                            onLongClick = { onForget(term) },
                        )
                        .padding(horizontal = 13.dp, vertical = 7.dp),
                )
            }
        }
    }
}
