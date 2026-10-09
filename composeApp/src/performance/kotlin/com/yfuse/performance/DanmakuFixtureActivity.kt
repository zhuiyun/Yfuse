package com.yfuse.performance

import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import com.yfuse.BuildConfig
import com.yfuse.core.data.DanmakuComment
import com.yfuse.core.data.DanmakuDisplayArea
import com.yfuse.core.data.DanmakuFontSize
import com.yfuse.core.data.DanmakuKind
import com.yfuse.core.data.DanmakuOpacity
import com.yfuse.core.data.DanmakuSpeed
import com.yfuse.core.designsystem.YfuseTheme
import com.yfuse.feature.player.DanmakuOverlay
import kotlinx.coroutines.delay

/** Exercises the production overlay with a deterministic 25-comment-per-second timeline. */
class DanmakuFixtureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        check(BuildConfig.APPLICATION_ID.endsWith(".benchmark"))
        enableEdgeToEdge()
        val comments =
            (0 until 2_400).map { index ->
                DanmakuComment(
                    timeMs = index * 40L,
                    text = "高密度弹幕 ${index.toString().padStart(4, '0')} · 播放画面测试",
                    kind = if (index % 11 == 0) DanmakuKind.Top else DanmakuKind.Scroll,
                )
            }
        setContent {
            var positionMs by remember { mutableLongStateOf(0L) }
            var drawn by remember { mutableStateOf(false) }
            var timelineReady by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                withFrameNanos {}
                withFrameNanos {}
                drawn = true
            }
            LaunchedEffect(Unit) {
                val startedAt = SystemClock.elapsedRealtime()
                while (true) {
                    positionMs = (SystemClock.elapsedRealtime() - startedAt) % 96_000L
                    if (positionMs >= 500L && !timelineReady) timelineReady = true
                    delay(250)
                }
            }
            YfuseTheme(dark = true) {
                Box(
                    Modifier.fillMaxSize().background(Color.Black).semantics {
                        testTagsAsResourceId = true
                        if (drawn && timelineReady) contentDescription = "danmaku-fixture-v1-ready"
                    },
                ) {
                    DanmakuOverlay(
                        comments = comments,
                        positionMs = positionMs,
                        playing = true,
                        playbackRate = 1f,
                        displayArea = DanmakuDisplayArea.Full,
                        fontSize = DanmakuFontSize.Standard,
                        speed = DanmakuSpeed.Standard,
                        opacity = DanmakuOpacity.Standard,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}
