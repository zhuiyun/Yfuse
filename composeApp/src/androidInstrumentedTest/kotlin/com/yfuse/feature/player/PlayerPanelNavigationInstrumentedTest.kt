package com.yfuse.feature.player

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yfuse.core.designsystem.AccessibilityOptions
import com.yfuse.core.designsystem.CinemaConnection
import com.yfuse.core.designsystem.YfuseTheme
import com.yfuse.launchMainActivityForInput
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PlayerPanelNavigationInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test(timeout = 120_000L)
    fun scrolled_playback_destinations_open_at_heading_and_return_remains_reachable() {
        launchMainActivityForInput().use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    YfuseTheme(dark = true, accessibility = AccessibilityOptions(reduceMotion = true)) {
                        SettingsFixture()
                    }
                }
            }
            click("播放设置")
            for (destination in listOf("睡眠定时", "手势设置", "画面设置")) {
                click(destination)
                val heading = "‹  $destination"
                await("Destination heading and back action: $destination") {
                    find(heading)?.let { node ->
                        val box = Rect().also(node::getBoundsInScreen)
                        node.isClickable && fullyVisible(node, box)
                    } == true
                }
                capture(destination)
                click(heading)
                await("Returned to playback header") { find("‹  播放设置") != null }
            }
        }
    }

    private fun click(label: String) {
        await("Reachable action: $label") {
            var node = find(label)
            while (node != null && !node.isClickable) node = node.parent
            if (node != null && node.isClickable && fullyVisible(node, Rect().also(node::getBoundsInScreen))) {
                assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                true
            } else {
                nodes().firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                SystemClock.sleep(150)
                false
            }
        }
        instrumentation.waitForIdleSync()
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()

        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null) return
            node.refresh()
            result += node
            for (index in 0 until node.childCount) visit(node.getChild(index))
        }
        visit(instrumentation.uiAutomation.rootInActiveWindow)
        return result
    }

    private fun find(label: String): AccessibilityNodeInfo? =
        nodes().firstOrNull {
            it.isVisibleToUser &&
                (it.text?.toString() == label || it.contentDescription?.toString() == label)
        }

    private fun fullyVisible(
        node: AccessibilityNodeInfo,
        box: Rect,
    ): Boolean {
        if (box.isEmpty || !node.isVisibleToUser) return false
        var parent = node.parent
        while (parent != null) {
            if (parent.isScrollable && !Rect().also(parent::getBoundsInScreen).contains(box)) return false
            parent = parent.parent
        }
        return true
    }

    private fun await(
        description: String,
        condition: () -> Boolean,
    ) {
        val deadline = SystemClock.uptimeMillis() + 15_000L
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        capture("failure")
        throw AssertionError(description)
    }

    private fun capture(name: String) {
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "player-panel-evidence")
        directory.mkdirs()
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}

@Composable
private fun SettingsFixture() {
    val playback = remember { mutableStateOf(PlaybackState(durationMs = 600_000L)) }
    SettingsPanel(
        kind = SettingsPanelKind.More,
        state = playback.value,
        playback = playback,
        containerLabel = "MP4",
        engineOptions = listOf("YCore" to true, "SenPlayer" to false),
        transcodeLabel = null,
        transcodeActive = false,
        castDevices = emptyList(),
        castingDeviceId = null,
        castDiscovering = false,
        castConnection = CinemaConnection.Idle,
        castError = null,
        castStatus = null,
        castPosition = null,
        castPositionSource = null,
        castCapabilities = null,
        castTransport = null,
        danmaku = DanmakuPanelState(),
        danmakuActions = DanmakuPanelActions(),
        onOpenDanmakuSearch = {},
        onOpenDanmakuSend = {},
        onSelectSubtitle = {},
        subtitleControls = SubtitleControlState(),
        subtitleActions = SubtitleControlActions(),
        remoteSubtitles = RemoteSubtitlePanelState(),
        remoteSubtitleActions = RemoteSubtitleActions(),
        audioControls = AudioControlState(),
        audioActions = AudioControlActions(),
        onSelectAudio = {},
        sleepTimer = SleepTimerState(),
        sleepTimerActions = SleepTimerActions(),
        onSelectEngine = {},
        onTranscode = {},
        onResetAdaptiveLearning = {},
        onNextDiscTitle = {},
        onNextDiscChapter = {},
        onShowDiscMenu = {},
        onExternalPlayer = null,
        onDiscoverCast = {},
        onCastTo = {},
        onStopCast = {},
        onLock = {},
        onOpenGestureHelp = {},
        watch = WatchRoomState(),
        onOpenWatchTogether = {},
        versions = emptyList(),
        selectedVersionId = null,
        onSelectVersion = {},
        skip = SkipSegmentState(),
        skipActions = SkipSegmentActions(),
        onDismiss = {},
    )
}
