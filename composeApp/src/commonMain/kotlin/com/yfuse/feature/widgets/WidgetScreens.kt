package com.yfuse.feature.widgets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.glass
import com.yfuse.core.designsystem.ThemeText as Text
import com.yfuse.feature.widgets.WidgetTextButton as TextButton

@Composable
internal fun FullWidgetDialog(
    onClose: () -> Unit,
    content: @Composable () -> Unit,
) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        WidgetDialogSystemBars(darkIcons = !LocalPalette.current.isDark)
        com.yfuse.core.designsystem.DialogBackdropHost {
            com.yfuse.core.designsystem
                .AppBackdrop { Box(Modifier.fillMaxSize().safeDrawingPadding()) { content() } }
        }
    }
}

@Composable
internal expect fun WidgetDialogSystemBars(darkIcons: Boolean)

@Composable
internal fun WidgetFrame(
    title: String,
    onBack: () -> Unit,
    action: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
            Text(
                title,
                Modifier.fillMaxWidth().padding(horizontal = 88.dp),
                color = LocalPalette.current.text,
                style = AppTypography.body.strong.copy(fontSize = 17.sp),
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Box(Modifier.align(Alignment.CenterStart)) {
                WidgetIconButton(com.yfuse.core.designsystem.AppIcons.ChevronLeft, "返回", onBack)
            }
            Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically, content = action)
        }
        content()
    }
}

@Composable
internal fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(AppShapes.card)
            .glass(AppShapes.card, LocalPalette.current.card, LocalPalette.current.border)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
internal fun SectionTitle(
    title: String,
    action: String? = null,
    onAction: () -> Unit = {},
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            title,
            Modifier.weight(1f),
            fontWeight = FontWeight.Bold,
            style = AppTypography.section.strong,
            color = LocalPalette.current.text,
        )
        if (action != null) TextButton(onClick = onAction) { Text(action) }
    }
}

@Composable
internal fun EmptyHint(message: String) {
    Text(message, color = LocalPalette.current.sub2, modifier = Modifier.padding(vertical = 18.dp))
}

internal val demoWidgetSource =
    """
    var WidgetMetadata = {
      id:'yfuse.demo',title:'Yfuse 卡片示例',version:'1.0.0',author:'Yfuse',description:'离线示例内容，用于体验首页编辑和 36 种展示样式。',
      modules:[{id:'movies',title:'我的精选',functionName:'getMovies',params:[{name:'kind',label:'内容类型',type:'enum',defaultValue:'all',enumOptions:[{title:'全部',value:'all'},{title:'电影',value:'movie'},{title:'剧集',value:'tv'}]}]}]
    };
    async function getMovies(params){
      var names=['暮色航线','山海之间','星际来信','雨后小镇','漫长夏日','远方的光'];
      return names.map(function(title,i){return {id:'demo-'+i,title:title,year:'2026',rating:8+i/10,description:'示例内容，用于展示卡片布局。点击可查看信息或搜索媒体库。',mediaType:i%2?'movie':'tv',date:'2026-10-'+(21+i),group:i%2?'电影':'剧集',progress:(i+1)/8,seasonNumber:1,episodeNumber:i+1};}).filter(function(x){return !params.kind||params.kind==='all'||x.mediaType===params.kind;});
    }
    """.trimIndent()
