package com.yfuse.tv.focus

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
