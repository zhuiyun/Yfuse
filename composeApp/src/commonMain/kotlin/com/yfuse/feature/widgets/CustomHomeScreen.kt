package com.yfuse.feature.widgets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yfuse.core.data.ThemePreferences
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.ThemeIcon
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.model.TmdbItem
import com.yfuse.core.widget.WidgetDatabase
import com.yfuse.core.widget.WidgetPage
import com.yfuse.core.widget.WidgetSourceContext
import com.yfuse.core.widget.WidgetStyle
import com.yfuse.core.widget.widgetRepository
import com.yfuse.core.widget.withBundledPagesFirst
import com.yfuse.core.designsystem.ThemeText as Text

/** Page selection belongs to this tab and never replaces the media-library root. */
internal fun customHomePage(
    database: WidgetDatabase,
    selectedId: String?,
): WidgetPage? {
    val visible = database.pages.filterNot { it.hidden }
    return visible.firstOrNull { it.id == selectedId }
        ?: visible.firstOrNull { it.id == database.defaultPageId }
        ?: visible.firstOrNull()
}

@Composable
fun CustomHomeScreen(
    preferences: ThemePreferences,
    onOpenTmdb: (TmdbItem, WidgetSourceContext?) -> Unit,
) {
    val repository = remember { widgetRepository() }
    val database by repository.database.collectAsState()
    var selected by rememberSaveable { mutableStateOf(preferences.customHomePageId) }
    var manager by rememberSaveable { mutableStateOf(false) }
    var choosing by remember { mutableStateOf(false) }
    val pageStates =
        androidx.compose.runtime.saveable
            .rememberSaveableStateHolder()
    val page = customHomePage(database, selected)
    val palette = LocalPalette.current
    com.yfuse.core.designsystem
        .StatusBarIconStyle(darkIcons = !palette.isDark)
    BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding()) {
        val horizontalPadding = widgetHomeHorizontalPadding(maxWidth)
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = horizontalPadding, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Row(
                        Modifier.defaultMinSize(minHeight = 48.dp).pressable(
                            enabled =
                                page != null && database.pages.count { !it.hidden } > 1,
                            onClick = { choosing = true },
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            page?.title ?: "我的首页",
                            Modifier.weight(1f, fill = false),
                            color = palette.text,
                            style = AppTypography.section.strong.copy(fontSize = 22.sp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (page != null &&
                            database.pages.count { !it.hidden } > 1
                        ) {
                            ThemeIcon(
                                AppIcons.ChevronDown,
                                "切换页面",
                                modifier = Modifier.padding(start = 8.dp).size(18.dp),
                                tint = palette.sub2,
                            )
                        }
                    }
                }
                WidgetHomeAction(AppIcons.Edit, "编辑首页", { manager = true }, label = "编辑")
            }
            if (page == null) {
                LazyColumn(
                    Modifier.weight(1f),
                    contentPadding = PaddingValues(start = 24.dp, top = 36.dp, end = 24.dp, bottom = 120.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    item {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            repeat(3) { index ->
                                Column(Modifier.weight(1f)) {
                                    WidgetPreviewArtwork(
                                        "preview-$index",
                                        Modifier
                                            .fillMaxWidth()
                                            .aspectRatio(
                                                if (index ==
                                                    1
                                                ) {
                                                    .58f
                                                } else {
                                                    .68f
                                                },
                                            ).clip(AppShapes.card),
                                    )
                                }
                            }
                        }
                    }
                    item {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "让首页，成为你的片单",
                                style = AppTypography.section.strong.copy(fontSize = 22.sp),
                                color = palette.text,
                                textAlign = TextAlign.Center,
                            )
                            Text(
                                "精选、榜单、追剧日历\n把喜欢的内容，按自己的方式排列。",
                                Modifier.padding(top = 12.dp),
                                color = palette.sub2,
                                style = AppTypography.body.reading,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                    item { WidgetPrimaryButton({ manager = true }, Modifier.fillMaxWidth()) { Text("开始布置首页") } }
                    item {
                        Text(
                            "支持自选组件与 ${WidgetStyle.entries.size} 种卡片样式",
                            color = palette.sub2,
                            style = AppTypography.caption.regular,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            } else {
                Box(Modifier.weight(1f)) {
                    pageStates.SaveableStateProvider(page.id) {
                        WidgetPageContent(
                            page,
                            repository,
                            onEdit = { manager = true },
                            onOpenTmdb = onOpenTmdb,
                            active =
                                !manager && !choosing,
                        )
                    }
                }
            }
        }
    }
    if (manager) WidgetWorkspaceScreen(repository, onClose = { manager = false })
    if (choosing) {
        WidgetSheet("切换页面", { choosing = false }) {
            LazyColumn {
                items(database.pages.filterNot { it.hidden }.withBundledPagesFirst(), key = { it.id }) { item ->
                    WidgetMenuRow(
                        item.title,
                        {
                            selected = item.id
                            preferences.customHomePageId = item.id
                            choosing = false
                        },
                        icon = AppIcons.Home,
                        subtitle = "${item.sections.count { !it.hidden }} 个栏目",
                        selected =
                            item.id == page?.id,
                    )
                }
            }
        }
    }
}
