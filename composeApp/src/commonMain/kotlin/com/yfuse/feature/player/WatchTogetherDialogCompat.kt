package com.yfuse.feature.player

import androidx.compose.runtime.Composable
import com.yfuse.core.sync.WatchControlMode
import com.yfuse.core.sync.WatchParticipant

/**
 * Keeps the existing player-chrome call site source-compatible while the room playlist is
 * introduced independently. Playlist entries are checked against, and started on, the player on
 * screen through [WatchPlaylistTarget], so the 100k-line-ish chrome file does not need a mechanical
 * rewrite.
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
        currentMediaTitle = ActivePlayback.state.value.title,
        onCreate = onCreate,
        onJoin = onJoin,
        onLeave = onLeave,
        onRequestControl = onRequestControl,
        onSetControlMode = onSetControlMode,
        onSetModerator = onSetModerator,
        onKickParticipant = onKickParticipant,
        playlistTarget = WatchPlaylistTarget.current,
        onDismiss = onDismiss,
    )
}
