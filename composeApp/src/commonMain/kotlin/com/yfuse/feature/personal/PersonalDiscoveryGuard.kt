package com.yfuse.feature.personal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.YfButton
import com.yfuse.core.personal.PersonalLibraryRepository
import org.koin.core.context.GlobalContext
import com.yfuse.core.designsystem.ThemeText as Text

/** Public discovery is not constrained by a child's media-server account. */
@Composable
fun PersonalDiscoveryGuard(
    onOpenLibrary: () -> Unit,
    content: @Composable () -> Unit,
) {
    val personal = remember { GlobalContext.get().get<PersonalLibraryRepository>() }
    val policy by personal.policy.collectAsState()
    if (policy.child) {
        Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("儿童资料", style = AppTypography.section.strong)
            Text("从已关联的媒体服务器浏览内容，服务器用户的权限继续生效。")
            YfButton("浏览我的媒体库", onOpenLibrary)
        }
    } else {
        content()
    }
}
