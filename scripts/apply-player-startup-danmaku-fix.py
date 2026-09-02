from pathlib import Path
import re


def load(path: str) -> str:
    return Path(path).read_text(encoding="utf-8")


def save(path: str, text: str) -> None:
    Path(path).write_text(text, encoding="utf-8")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one match, found {count}")
    return text.replace(old, new, 1)


def regex_once(text: str, pattern: str, replacement: str, label: str) -> str:
    updated, count = re.subn(pattern, replacement, text, count=1, flags=re.S)
    if count != 1:
        raise SystemExit(f"{label}: expected one match, found {count}")
    return updated


# Danmaku: keep the visual clock monotonic across pause / micro-buffer pulses while still
# accepting a real backward seek (>3 s) and correcting forward drift promptly.
path = "composeApp/src/commonMain/kotlin/com/yfuse/feature/player/DanmakuOverlay.kt"
text = load(path)
text = replace_once(
    text,
    "private const val POSITION_RESET_THRESHOLD_MS = 1_000L\n",
    "private const val POSITION_RESET_THRESHOLD_MS = 1_000L\nprivate const val BACKWARD_SEEK_RESET_THRESHOLD_MS = 3_000L\n",
    "danmaku threshold",
)
text = regex_once(
    text,
    r"    LaunchedEffect\(positionMs, playing\) \{.*?\n    \}\n    LaunchedEffect\(playing\) \{",
    '''    LaunchedEffect(positionMs, playing) {
        val driftMs = positionMs - renderedPositionMs
        when {
            driftMs > POSITION_RESET_THRESHOLD_MS -> renderedPositionMs = positionMs
            driftMs < -BACKWARD_SEEK_RESET_THRESHOLD_MS -> renderedPositionMs = positionMs
            // Pauses and brief transport stalls freeze the interpolated clock. Never snap
            // backwards to a lagging engine tick and replay already-visible comments.
            !playing -> Unit
        }
    }
    LaunchedEffect(playing) {''',
    "danmaku position effect",
)
text = regex_once(
    text,
    r"                val reported = latestReportedPosition\n                if \(abs\(reported - renderedPositionMs\) > POSITION_RESET_THRESHOLD_MS\) \{\n                    renderedPositionMs = reported\n                \}",
    '''                val reported = latestReportedPosition
                val driftMs = reported - renderedPositionMs
                when {
                    driftMs > POSITION_RESET_THRESHOLD_MS -> renderedPositionMs = reported
                    driftMs < -BACKWARD_SEEK_RESET_THRESHOLD_MS -> renderedPositionMs = reported
                }''',
    "danmaku frame correction",
)
text = replace_once(text, "import kotlin.math.abs\n", "", "danmaku abs import")
save(path, text)


# NativeDirect: a synchronous range cache miss shorter than 300 ms is transport work, not a
# user-visible rebuffer. Freeze the media clock immediately but debounce the public buffering state.
path = "composeApp/src/androidMain/kotlin/com/yfuse/core2/android/AndroidNativeDirectYPlayer.kt"
text = load(path)
text = replace_once(
    text,
    "import kotlinx.coroutines.CoroutineScope\n",
    "import kotlinx.coroutines.CoroutineScope\nimport kotlinx.coroutines.Dispatchers\n",
    "native Dispatchers import",
)
text = replace_once(
    text,
    "        private var transportReadBlocked = false\n        private var droppedFrames = 0\n",
    "        private var transportReadBlocked = false\n\n        @Volatile\n        private var transportBufferingVisible = false\n\n        @Volatile\n        private var transportBlockGeneration = 0L\n        private var droppedFrames = 0\n",
    "native transport fields",
)
text = replace_once(text, "playing = firstVideoFrameRendered && !transportReadBlocked,", "playing = firstVideoFrameRendered && !transportBufferingVisible,", "native start playing")
text = replace_once(text, "buffering = !firstVideoFrameRendered || transportReadBlocked,", "buffering = !firstVideoFrameRendered || transportBufferingVisible,", "native start buffering")
text = replace_once(text, "playing = requestedPlay && firstVideoFrameRendered && !transportReadBlocked && !isEnded(),", "playing = requestedPlay && firstVideoFrameRendered && !transportBufferingVisible && !isEnded(),", "native publish playing")
text = replace_once(text, "buffering = requestedPlay && (!firstVideoFrameRendered || transportReadBlocked) && !isEnded(),", "buffering = requestedPlay && (!firstVideoFrameRendered || transportBufferingVisible) && !isEnded(),", "native publish buffering")
text = replace_once(text, "                                !transportReadBlocked &&\n", "                                !transportBufferingVisible &&\n", "native first-frame playing")
text = replace_once(text, "                        buffering = requestedPlay && transportReadBlocked,\n", "                        buffering = requestedPlay && transportBufferingVisible,\n", "native first-frame buffering")
text = regex_once(
    text,
    r"        private fun onTransportBlockingReadStateChanged\(blocked: Boolean\) \{.*?\n        \}\n\n        private fun abortIfReleased\(\) \{",
    '''        private fun onTransportBlockingReadStateChanged(blocked: Boolean) {
            if (transportReadBlocked == blocked || released) return
            val nowNs = System.nanoTime()
            if (blocked) {
                val frozenPositionUs = currentPositionUs()
                monotonicPositionFloorUs = frozenPositionUs
                wallClock.pause(frozenPositionUs, nowNs)
                transportReadBlocked = true
                val generation = ++transportBlockGeneration
                scope.launch(Dispatchers.Default) {
                    delay(TRANSPORT_BUFFERING_DEBOUNCE_MS)
                    if (!released && transportReadBlocked && transportBlockGeneration == generation) {
                        transportBufferingVisible = true
                        mutableState.updateState { current ->
                            if (released || current.phase == YPlaybackPhase.Failed || current.phase == YPlaybackPhase.Ended) {
                                current
                            } else {
                                current.copy(playing = false, buffering = requestedPlay)
                            }
                        }
                    }
                }
                return
            }

            transportReadBlocked = false
            ++transportBlockGeneration
            transportBufferingVisible = false
            if (requestedPlay) {
                val resumePositionUs = audioClockSnapshot()?.positionUs ?: wallClock.positionUs(nowNs)
                monotonicPositionFloorUs = maxOf(monotonicPositionFloorUs, resumePositionUs)
                wallClock.start(monotonicPositionFloorUs, nowNs)
            }
            mutableState.updateState { current ->
                if (released || current.phase == YPlaybackPhase.Failed || current.phase == YPlaybackPhase.Ended) {
                    current
                } else {
                    current.copy(
                        playing = requestedPlay && firstVideoFrameRendered,
                        buffering = requestedPlay && !firstVideoFrameRendered,
                    )
                }
            }
        }

        private fun abortIfReleased() {''',
    "native blocking callback",
)
text = replace_once(
    text,
    "private const val PUMP_IDLE_DELAY_MS = 1L\n",
    "private const val PUMP_IDLE_DELAY_MS = 1L\nprivate const val TRANSPORT_BUFFERING_DEBOUNCE_MS = 300L\n",
    "native debounce constant",
)
save(path, text)


# Give the startup-critical first OkHttp range a whole-call deadline. Once one valid 206 has been
# accepted, subsequent ranges retain the existing 20 s connect/read/write policy.
path = "composeApp/src/androidMain/kotlin/com/yfuse/core2/android/AndroidHttpMediaTransport.kt"
text = load(path)
text = replace_once(
    text,
    "    private val redirectState: AndroidHttpMediaRedirectState? = null,\n) : YMediaTransport {",
    "    private val redirectState: AndroidHttpMediaRedirectState? = null,\n    private val callTimeoutSeconds: Long? = null,\n) : YMediaTransport {",
    "http constructor",
)
text = replace_once(
    text,
    "            .followSslRedirects(false)\n            .build()",
    "            .followSslRedirects(false)\n            .apply { callTimeoutSeconds?.let { callTimeout(it, TimeUnit.SECONDS) } }\n            .build()",
    "http call timeout",
)
save(path, text)

path = "composeApp/src/androidMain/kotlin/com/yfuse/core2/android/AndroidAdaptiveHttpMediaTransport.kt"
text = load(path)
text = replace_once(
    text,
    "            redirectState = routeState.redirectState,\n        )",
    "            redirectState = routeState.redirectState,\n            callTimeoutSeconds = if (routeState.hasAcceptedRange) null else 8L,\n        )",
    "adaptive startup timeout",
)
save(path, text)
