package com.yfuse.feature.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.YfFormField
import com.yfuse.feature.widgets.WidgetRetryButton
import com.yfuse.feature.widgets.WidgetSecondaryButton
import com.yfuse.core.designsystem.ThemeText as Text

/** Resource choices live inside the existing detail page, beside its library source rail. */
@Composable
internal fun DetailCatalogResources(
    catalog: DetailCatalog,
    selected: Boolean,
    onSelect: (String) -> Unit,
    onCoordinate: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by catalog.resources.collectAsState()
    if (state.routes.isEmpty() &&
        state.error == null &&
        state.metadataError == null &&
        !state.loading &&
        !state.needsEpisodeInput
    ) {
        return
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.needsEpisodeInput) {
            var season by remember(state.season) { mutableStateOf(state.season.toString()) }
            var episode by remember(state.episode) { mutableStateOf(state.episode.toString()) }

            fun applyCoordinate() {
                val s = season.toIntOrNull() ?: return
                val e = episode.toIntOrNull() ?: return
                onCoordinate(s, e)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                YfFormField(season, { value ->
                    if (value.length <= 4 && value.all(Char::isDigit)) {
                        season = value
                        applyCoordinate()
                    }
                }, label = "第几季", modifier = Modifier.weight(1f))
                YfFormField(episode, { value ->
                    if (value.length <= 4 && value.all(Char::isDigit)) {
                        episode = value
                        applyCoordinate()
                    }
                }, label = "第几集", modifier = Modifier.weight(1f))
            }
        }
        Text("组件资源", style = AppTypography.section.strong, color = LocalPalette.current.text)
        LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(state.routes, key = { it.id }) { route ->
                WidgetSecondaryButton(enabled = !state.loading, onClick = { onSelect(route.id) }) {
                    val name = route.title.takeIf { it != catalog.context.media.title }?.ifBlank { null } ?: "组件来源"
                    Text(
                        if (selected &&
                            state.selectedId == route.id
                        ) {
                            "✓ $name"
                        } else {
                            name
                        },
                        style = AppTypography.body.medium,
                    )
                }
            }
        }
        state.metadataError?.let {
            Text(it, style = AppTypography.caption.regular, color = LocalPalette.current.sub)
            WidgetRetryButton(catalog::retryMetadata, busy = state.loading)
        }
        if (state.loading) Text("正在查找资源…", style = AppTypography.caption.regular, color = LocalPalette.current.sub)
        state.error?.let {
            Text(it, style = AppTypography.caption.regular, color = LocalPalette.current.sub)
            WidgetRetryButton(catalog::retryResources, busy = state.loading)
        }
    }
}
