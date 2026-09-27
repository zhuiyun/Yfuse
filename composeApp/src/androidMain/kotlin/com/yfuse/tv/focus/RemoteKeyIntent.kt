package com.yfuse.tv.focus

enum class TvFocusDirection {
    Up,
    Down,
    Left,
    Right,
}

enum class RemotePhysicalKey {
    DirectionUp,
    DirectionDown,
    DirectionLeft,
    DirectionRight,
    Activate,
    Back,
    Menu,
    PlayPause,
    Play,
    Pause,
    Stop,
    FastForward,
    Rewind,
    Next,
    Previous,
    Search,
    Info,
    Captions,
    Guide,
}

enum class RemoteKeyPhase {
    Down,
    Up,
}

data class RemoteKeyInput(
    val key: RemotePhysicalKey,
    val phase: RemoteKeyPhase,
    val repeatCount: Int = 0,
    val isLongPress: Boolean = false,
) {
    init {
        require(repeatCount >= 0) { "repeatCount must be non-negative" }
    }
}

sealed interface RemoteIntent {
    data class Navigate(
        val direction: TvFocusDirection,
        val repeated: Boolean,
    ) : RemoteIntent

    data object Activate : RemoteIntent

    data object OpenContextMenu : RemoteIntent

    data object Back : RemoteIntent

    data object Menu : RemoteIntent

    data object PlayPause : RemoteIntent

    data object Play : RemoteIntent

    data object Pause : RemoteIntent

    data object Stop : RemoteIntent

    data object FastForward : RemoteIntent

    data object Rewind : RemoteIntent

    data object Next : RemoteIntent

    data object Previous : RemoteIntent

    data object Search : RemoteIntent

    data object Info : RemoteIntent

    data object Captions : RemoteIntent

    data object Guide : RemoteIntent
}

/** How long 确定 is held before it opens a card's quick actions instead of the card. */
const val REMOTE_LONG_PRESS_MILLIS = 600L

/**
 * Decides when a held key has become a long press: [thresholdMs] after its first key-down, once
 * per hold, on the first key-down (a repeat) at or past that point.
 *
 * The platform's own long-press flag cannot say this. The input dispatcher sets it on a key's
 * first repeat, which it synthesises after the system long-press timeout — 400 or 500 ms,
 * whichever the device ships — and a remote whose driver repeats keys itself gets it on that
 * driver's second report, as early as 250 ms. Nor can a repeat's own down time: such a driver
 * stamps every repeat as a fresh press. So the first key-down's time is kept here, per key.
 */
class RemoteLongPressClock(
    private val thresholdMs: Long = REMOTE_LONG_PRESS_MILLIS,
) {
    private val downAt = mutableMapOf<Int, Long>()
    private val fired = mutableSetOf<Int>()

    /** A key-down, first or repeat. True exactly once per hold. */
    fun onDown(
        keyCode: Int,
        repeatCount: Int,
        eventTimeMs: Long,
    ): Boolean {
        // A fresh press starts the clock again. So does a repeat whose first press went to
        // another surface: the hold is timed from where this one first saw it.
        if (repeatCount == 0 || keyCode !in downAt) {
            downAt[keyCode] = eventTimeMs
            fired -= keyCode
            return false
        }
        val start = downAt.getValue(keyCode)
        if (keyCode in fired || eventTimeMs - start < thresholdMs) return false
        fired += keyCode
        return true
    }

    fun onUp(keyCode: Int) {
        downAt -= keyCode
        fired -= keyCode
    }
}

/**
 * What a panel opened by holding a key does with the rest of that hold.
 *
 * The panel takes focus while 确定 is still down, so the key's remaining repeats and its release
 * arrive at the panel's first row. A row has not seen that press start, and a repeat looks to it
 * like one: it would take the release as a click and run the action the person never chose. The
 * hold belongs to the gesture that opened the panel, so the panel ignores it — every repeat and
 * the release — until a fresh press of the key.
 */
class RemoteHoldCarryOver {
    private val armed = mutableSetOf<Int>()

    /** True when this event belongs to the hold that opened the panel and must go no further. */
    fun swallow(
        keyCode: Int,
        down: Boolean,
        repeatCount: Int,
    ): Boolean {
        if (keyCode in armed) return false
        if (down && repeatCount == 0) {
            armed += keyCode
            return false
        }
        return true
    }
}

/**
 * Converts a physical remote event into one semantic action.
 *
 * Activate fires on key-up so a held centre key can become a context-menu gesture without first
 * activating the item. Navigation and transport controls fire on key-down for responsive repeat.
 */
object RemoteIntentPolicy {
    fun map(input: RemoteKeyInput): RemoteIntent? {
        if (
            input.key == RemotePhysicalKey.Activate &&
            input.phase == RemoteKeyPhase.Down &&
            input.isLongPress
        ) {
            return RemoteIntent.OpenContextMenu
        }
        if (input.key == RemotePhysicalKey.Activate) {
            return if (input.phase == RemoteKeyPhase.Up && input.repeatCount == 0) {
                RemoteIntent.Activate
            } else {
                null
            }
        }
        if (input.phase != RemoteKeyPhase.Down) return null

        return when (input.key) {
            RemotePhysicalKey.DirectionUp ->
                RemoteIntent.Navigate(TvFocusDirection.Up, input.repeatCount > 0)

            RemotePhysicalKey.DirectionDown ->
                RemoteIntent.Navigate(TvFocusDirection.Down, input.repeatCount > 0)

            RemotePhysicalKey.DirectionLeft ->
                RemoteIntent.Navigate(TvFocusDirection.Left, input.repeatCount > 0)

            RemotePhysicalKey.DirectionRight ->
                RemoteIntent.Navigate(TvFocusDirection.Right, input.repeatCount > 0)

            RemotePhysicalKey.Activate -> error("activate is handled before key-down dispatch")

            RemotePhysicalKey.Back -> if (input.repeatCount == 0) RemoteIntent.Back else null
            RemotePhysicalKey.Menu -> if (input.repeatCount == 0) RemoteIntent.Menu else null
            RemotePhysicalKey.PlayPause -> if (input.repeatCount == 0) RemoteIntent.PlayPause else null
            RemotePhysicalKey.Play -> if (input.repeatCount == 0) RemoteIntent.Play else null
            RemotePhysicalKey.Pause -> if (input.repeatCount == 0) RemoteIntent.Pause else null
            RemotePhysicalKey.Stop -> if (input.repeatCount == 0) RemoteIntent.Stop else null
            RemotePhysicalKey.FastForward -> RemoteIntent.FastForward
            RemotePhysicalKey.Rewind -> RemoteIntent.Rewind
            RemotePhysicalKey.Next -> if (input.repeatCount == 0) RemoteIntent.Next else null
            RemotePhysicalKey.Previous -> if (input.repeatCount == 0) RemoteIntent.Previous else null
            RemotePhysicalKey.Search -> if (input.repeatCount == 0) RemoteIntent.Search else null
            RemotePhysicalKey.Info -> if (input.repeatCount == 0) RemoteIntent.Info else null
            RemotePhysicalKey.Captions -> if (input.repeatCount == 0) RemoteIntent.Captions else null
            RemotePhysicalKey.Guide -> if (input.repeatCount == 0) RemoteIntent.Guide else null
        }
    }
}
