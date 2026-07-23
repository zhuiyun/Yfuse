package com.yfuse.feature.search

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.GlassCard
import com.yfuse.core.designsystem.LocalGlass

@Composable
fun SearchScreen(component: SearchComponent) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        GlassCard {
            Text(
                "搜索 · 即将推出",
                style = MaterialTheme.typography.bodyLarge,
                color = LocalGlass.current.onGlassMuted,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            )
        }
    }
}
