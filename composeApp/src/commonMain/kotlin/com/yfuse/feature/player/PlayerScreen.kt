package com.yfuse.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun PlayerScreen(component: PlayerComponent) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val url = component.streamUrl
        if (url != null) {
            VideoPlayer(
                url = url,
                startPositionMs = component.startPositionMs,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                "无法播放:没有可用的服务器",
                color = Color.White,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        Surface(
            shape = CircleShape,
            color = Color(0x66000000),
            modifier = Modifier.statusBarsPadding().padding(8.dp).align(Alignment.TopStart),
        ) {
            Box(Modifier.clickable(onClick = component.onBack).padding(6.dp)) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回", tint = Color.White)
            }
        }
    }
}
