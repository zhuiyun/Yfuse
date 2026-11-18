package com.yfuse.feature.player

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yfuse.core.designsystem.AccessibilityOptions
import com.yfuse.core.designsystem.CinemaConnection
import com.yfuse.core.designsystem.YfuseTheme
import com.yfuse.core.playback.PlaybackFailureKind
import com.yfuse.launchMainActivityForInput
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import androidx.compose.ui.graphics.Color as ComposeColor

@RunWith(AndroidJUnit4::class)
class PlayerPanelNavigationInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test(timeout = 150_000L)
    fun playback_problem_captions_are_readable_on_bright_and_dark_pictures() {
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "problem-contrast")
        directory.mkdirs()
        val rows = mutableListOf<JSONObject>()
        for (dark in listOf(false, true)) {
            val theme = if (dark) "dark" else "light"
            rows += captureProblemCaptions(dark, ComposeColor.Black, "$theme-black", directory)
            rows += captureProblemCaptions(dark, ComposeColor.White, "$theme-white", directory)
        }
        rows += captureProblemCaptions(true, null, "dark-default", directory)
        File(directory, "measurements.json").writeText(JSONArray(rows).toString(2))
        val dim = rows.filter { it.getDouble("contrast") < 4.5 }
        assertTrue("Playback diagnostic captions below 4.5:1: $dim", dim.isEmpty())
    }

    private fun captureProblemCaptions(
        dark: Boolean,
        backing: ComposeColor?,
        name: String,
        directory: File,
    ): List<JSONObject> {
        val labels = listOf("连接与读取", "播放器", "视频输出", "声音输出", "当前音轨", "前向缓冲", "实时读取", "网络恢复")
        return launchMainActivityForInput().use { scenario ->
            scenario.onActivity { it.setContent { ProblemContrastFixture(dark, backing) } }
            await("Diagnostic captions visible on $name") { labels.all { find(it) != null } }
            instrumentation.waitForIdleSync()
            val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            val pixels = requireNotNull(screenshot.copy(Bitmap.Config.ARGB_8888, false))
            try {
                val file = File(directory, "$name.png")
                file.outputStream().use { pixels.compress(Bitmap.CompressFormat.PNG, 100, it) }
                labels.map { label ->
                    val bounds = Rect().also(requireNotNull(find(label))::getBoundsInScreen)
                    JSONObject()
                        .put("screen", name)
                        .put("label", label)
                        .put("bounds", bounds.flattenToString())
                        .put("contrast", captionContrast(pixels, bounds))
                }
            } finally {
                pixels.recycle()
                screenshot.recycle()
            }
        }
    }

    private fun captionContrast(
        bitmap: Bitmap,
        bounds: Rect,
    ): Double {
        require(!bounds.isEmpty && Rect(0, 0, bitmap.width, bitmap.height).contains(bounds))
        val counts = mutableMapOf<Int, Int>()
        for (y in bounds.top until bounds.bottom) {
            for (x in bounds.left until bounds.right) {
                val colour = bitmap.getPixel(x, y)
                counts[colour] = counts.getOrDefault(colour, 0) + 1
            }
        }
        val background = Color.luminance(counts.maxBy { it.value }.key).toDouble()
        val ratios =
            counts.map { (colour, count) ->
                val ink = Color.luminance(colour).toDouble()
                (maxOf(ink, background) + 0.05) / (minOf(ink, background) + 0.05) to count
            }
        var pixels = 0
        return ratios
            .sortedByDescending { it.first }
            .first { (_, count) ->
                pixels += count
                pixels >= 7
            }.first
    }

    @Test
    fun refused_media_read_shows_the_network_stage_and_retry_advice() {
        val failures = PlaybackProxyFailureState()
        failures.recordRefusedRead(failures.beginSource())
        val evidence = failures.snapshot()
        val reason = requireNotNull(senPlayerSourceFailure(evidence.httpStatus, evidence.refusedRead))
        val state = PlaybackState().forSenPlayerFailure(reason.message, reason.kind)
        launchMainActivityForInput().use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    YfuseTheme(dark = true, accessibility = AccessibilityOptions(reduceMotion = true)) {
                        PlaybackProblemDialog(remember { mutableStateOf(state) }, onDismiss = {})
                    }
                }
            }
            await("Source read failure and retry advice are visible") {
                find("连接与读取") != null && find("检查服务器可达性与下载速度，再重试当前片源。") != null
            }
            assertTrue(find("阶段未确定") == null)
            capture("source-read-refused")
        }
    }

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
private fun ProblemContrastFixture(
    dark: Boolean,
    backing: ComposeColor?,
) {
    YfuseTheme(dark = dark, accessibility = AccessibilityOptions(reduceMotion = true)) {
        val fill = Modifier.fillMaxSize()
        Box(if (backing != null) fill.background(backing) else fill) {
            val playback =
                remember {
                    mutableStateOf(PlaybackState().forSenPlayerFailure("测试读取被拒绝", PlaybackFailureKind.Network))
                }
            PlaybackProblemDialog(playback, onDismiss = {})
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
