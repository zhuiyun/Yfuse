package com.yfuse.tv.player

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.view.KeyEvent
import com.yfuse.tv.focus.AndroidRemoteKeyMapper
import com.yfuse.tv.focus.RemotePhysicalKey

private const val TAP_SEEK_MS = 10_000L
private const val HOLD_SEEK_STEP_MS = 3_000L
private const val HOLD_SEEK_FAST_STEP_MS = 9_000L
private const val HOLD_SEEK_RAMP_MS = 3_000L
private const val SEEK_DISPATCH_DEBOUNCE_MS = 250L

/** Operations exposed by PlayerActivity without leaking an engine or a server route into TV code. */
internal class TvPlaybackActions(
    val currentPositionMs: () -> Long,
    val durationMs: () -> Long,
    val togglePlayPause: () -> Unit,
    val play: () -> Unit,
    val pause: () -> Unit,
    val seekTo: (Long) -> Unit,
    val previous: () -> Unit,
    val next: () -> Unit,
)

/**
 * One mapping for Android TV, Google TV, HDMI-CEC remotes and media keyboards.
 *
 * Hidden chrome treats left/right as timeline input. The first press seeks ten seconds and reveals
 * chrome; held repeats are merged to at most one engine seek every 250 ms, with a final flush on
 * release. Once chrome is visible, a fresh D-pad press is left to Compose focus navigation.
 */
internal class TvRemoteInputController(
    private val chrome: TvPlayerChromeController,
    private val playback: TvPlaybackActions,
    private val nowMs: () -> Long,
) {
    private val consumedDownKeys = mutableSetOf<Int>()
    private var heldSeekKey: Int? = null
    private var heldSeekStartedMs = 0L
    private var seekTargetMs = 0L
    private var lastSeekDispatchMs = Long.MIN_VALUE
    private var seekDirty = false

    fun dispatch(event: KeyEvent): Boolean =
        dispatchKey(
            action = event.action,
            keyCode = event.keyCode,
            repeatCount = event.repeatCount,
            eventTime = event.eventTime,
        )

    internal fun dispatchKey(
        action: Int,
        keyCode: Int,
        repeatCount: Int = 0,
        eventTime: Long = nowMs(),
    ): Boolean =
        when (action) {
            KeyEvent.ACTION_DOWN -> dispatchDown(keyCode, repeatCount, eventTime)
            KeyEvent.ACTION_UP -> dispatchUp(keyCode)
            else -> false
        }

    private fun dispatchDown(
        keyCode: Int,
        repeatCount: Int,
        eventTime: Long,
    ): Boolean {
        // SPACE is a useful keyboard fallback over the video, but panels contain search/chat
        // fields where it must remain text input. Their buttons are still activated by D-pad
        // center or Enter through the shared physical-key mapper.
        if (keyCode == KeyEvent.KEYCODE_SPACE && chrome.state.value.layer == TvPlayerChromeLayer.Panel) {
            return false
        }
        val physicalKey = playbackPhysicalKey(keyCode) ?: return false
        if (keyCode == heldSeekKey) {
            updateHeldSeek(directionForSeekKey(physicalKey), eventTime)
            consumedDownKeys += keyCode
            return true
        }

        return when (physicalKey) {
            RemotePhysicalKey.DirectionLeft,
            RemotePhysicalKey.DirectionRight,
            -> {
                if (chrome.state.value.layer == TvPlayerChromeLayer.Hidden) {
                    startHeldSeek(keyCode, directionForSeekKey(physicalKey), eventTime)
                    consumeDown(keyCode)
                } else {
                    chrome.showControls()
                    false
                }
            }

            RemotePhysicalKey.DirectionUp,
            RemotePhysicalKey.DirectionDown,
            -> {
                if (chrome.state.value.layer == TvPlayerChromeLayer.Hidden) {
                    chrome.showControls()
                    consumeDown(keyCode)
                } else {
                    chrome.showControls()
                    false
                }
            }

            RemotePhysicalKey.Activate -> {
                val state = chrome.state.value
                if (state.layer == TvPlayerChromeLayer.Hidden || !state.controlsHaveFocus) {
                    if (repeatCount == 0) {
                        chrome.showControls()
                        playback.togglePlayPause()
                    }
                    consumeDown(keyCode)
                } else {
                    chrome.showControls()
                    false
                }
            }

            RemotePhysicalKey.Back -> {
                if (chrome.state.value.hasDismissibleLayer) {
                    if (repeatCount == 0) chrome.closeTop()
                    consumeDown(keyCode)
                } else {
                    false
                }
            }

            RemotePhysicalKey.Menu -> {
                if (repeatCount == 0) chrome.showControls()
                consumeDown(keyCode)
            }

            RemotePhysicalKey.PlayPause -> {
                if (repeatCount == 0) {
                    chrome.showControls()
                    playback.togglePlayPause()
                }
                consumeDown(keyCode)
            }

            RemotePhysicalKey.Play -> {
                if (repeatCount == 0) {
                    chrome.showControls()
                    playback.play()
                }
                consumeDown(keyCode)
            }

            RemotePhysicalKey.Pause -> {
                if (repeatCount == 0) {
                    chrome.showControls()
                    playback.pause()
                }
                consumeDown(keyCode)
            }

            RemotePhysicalKey.Stop -> {
                // Video playback has no background stopped-but-retained state. Pause preserves
                // progress and the Activity's existing Back path remains the explicit close.
                if (repeatCount == 0) {
                    chrome.showControls()
                    playback.pause()
                }
                consumeDown(keyCode)
            }

            RemotePhysicalKey.Rewind,
            RemotePhysicalKey.FastForward,
            -> {
                startHeldSeek(keyCode, directionForSeekKey(physicalKey), eventTime)
                consumeDown(keyCode)
            }

            RemotePhysicalKey.Previous -> {
                if (repeatCount == 0) playback.previous()
                consumeDown(keyCode)
            }

            RemotePhysicalKey.Next -> {
                if (repeatCount == 0) playback.next()
                consumeDown(keyCode)
            }

            RemotePhysicalKey.Captions -> {
                if (repeatCount == 0) chrome.openTracks()
                consumeDown(keyCode)
            }

            RemotePhysicalKey.Info -> {
                if (repeatCount == 0) chrome.openInfo()
                consumeDown(keyCode)
            }

            RemotePhysicalKey.Search,
            RemotePhysicalKey.Guide,
            -> false
        }
    }

    private fun dispatchUp(keyCode: Int): Boolean {
        if (keyCode == heldSeekKey) {
            flushSeek()
            heldSeekKey = null
            seekDirty = false
            chrome.finishSeekPreview()
            consumedDownKeys.remove(keyCode)
            return true
        }
        return consumedDownKeys.remove(keyCode)
    }

    private fun startHeldSeek(
        keyCode: Int,
        direction: Int,
        eventTime: Long,
    ) {
        heldSeekKey?.let {
            flushSeek()
            consumedDownKeys.remove(it)
        }
        val now = eventTime.takeIf { it > 0L } ?: nowMs()
        heldSeekKey = keyCode
        heldSeekStartedMs = now
        seekTargetMs = boundedSeekTarget(playback.currentPositionMs(), direction * TAP_SEEK_MS)
        lastSeekDispatchMs = Long.MIN_VALUE
        seekDirty = true
        chrome.showControls()
        chrome.updateSeekPreview(seekTargetMs)
        flushSeek(now)
    }

    private fun updateHeldSeek(
        direction: Int,
        eventTime: Long,
    ) {
        val now = eventTime.takeIf { it > 0L } ?: nowMs()
        val heldForMs = (now - heldSeekStartedMs).coerceAtLeast(0L)
        val step = if (heldForMs >= HOLD_SEEK_RAMP_MS) HOLD_SEEK_FAST_STEP_MS else HOLD_SEEK_STEP_MS
        seekTargetMs = boundedSeekTarget(seekTargetMs, direction * step)
        seekDirty = true
        chrome.updateSeekPreview(seekTargetMs)
        if (elapsedSinceLastDispatch(now) >= SEEK_DISPATCH_DEBOUNCE_MS) {
            // Reset the same inactivity timer as a touch scrub; long remote seeks must not make
            // their own timeline disappear half way through the hold.
            chrome.showControls()
            flushSeek(now)
        }
    }

    private fun flushSeek(now: Long = nowMs()) {
        if (!seekDirty) return
        playback.seekTo(seekTargetMs)
        lastSeekDispatchMs = now
        seekDirty = false
    }

    private fun elapsedSinceLastDispatch(now: Long): Long =
        if (lastSeekDispatchMs == Long.MIN_VALUE) Long.MAX_VALUE else (now - lastSeekDispatchMs).coerceAtLeast(0L)

    private fun boundedSeekTarget(
        baseMs: Long,
        deltaMs: Long,
    ): Long {
        val durationMs = playback.durationMs()
        val upper = durationMs.takeIf { it > 0L } ?: Long.MAX_VALUE
        val raw =
            if (deltaMs > 0L && baseMs > Long.MAX_VALUE - deltaMs) {
                Long.MAX_VALUE
            } else {
                baseMs + deltaMs
            }
        return raw.coerceIn(0L, upper)
    }

    private fun consumeDown(keyCode: Int): Boolean {
        consumedDownKeys += keyCode
        return true
    }
}

private fun playbackPhysicalKey(keyCode: Int): RemotePhysicalKey? =
    AndroidRemoteKeyMapper.physicalKey(keyCode)
        ?: when (keyCode) {
            KeyEvent.KEYCODE_SPACE,
            -> RemotePhysicalKey.PlayPause
            else -> null
        }

private fun directionForSeekKey(key: RemotePhysicalKey): Int =
    when (key) {
        RemotePhysicalKey.DirectionLeft,
        RemotePhysicalKey.Rewind,
        -> -1
        else -> 1
    }

internal fun isTelevisionDevice(context: Context): Boolean {
    val configuredForTelevision =
        context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK ==
            Configuration.UI_MODE_TYPE_TELEVISION
    val uiModeManager = context.getSystemService(UiModeManager::class.java)
    return configuredForTelevision || uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
}
