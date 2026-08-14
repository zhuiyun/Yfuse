package com.yfuse.feature.player

import androidx.compose.runtime.Composable
import com.yfuse.core.sync.WatchControlMode
import com.yfuse.core.sync.WatchParticipant

/**
 * Keeps the existing player-chrome call site source-compatible while the room playlist is
 * introduced independently. PlayerControls can opt into playlist jump metadata in a focused
 * follow-up without forcing the entire 100k-line-ish chrome file through a mechanical rewrite.
 */
@Composable
internal fun WatchTogetherDialog(
    endpoint: String,
    connecting: Boolean,
    connected: Boolean,
    roomCode: String?,
    isHost: Boolean,
    canControl: Boolean,
    controlMode: WatchControlMode,
    participantCount: Int,
    participants: List<WatchParticipant>,
    error: String?,
    controlRequested: Boolean,
    onCreate: (String) -> Unit,
    onJoin: (String, String) -> Unit,
    onLeave: () -> Unit,
    onRequestControl: () -> Unit,
    onSetControlMode: (WatchControlMode) -> Unit,
    onSetModerator: (String, Boolean) -> Unit,
    onKickParticipant: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    WatchTogetherDialog(
        endpoint = endpoint,
        connecting = connecting,
        connected = connected,
        roomCode = roomCode,
        isHost = isHost,
        canControl = canControl,
        controlMode = controlMode,
        participantCount = participantCount,
        participants = participants,
        error = error,
        controlRequested = controlRequested,
        currentMediaTitle = "",
        onCreate = onCreate,
        onJoin = onJoin,
        onLeave = onLeave,
        onRequestControl = onRequestControl,
        onSetControlMode = onSetControlMode,
        onSetModerator = onSetModerator,
        onKickParticipant = onKickParticipant,
        onPlaylistPlay = {},
        onDismiss = onDismiss,
    )
}
