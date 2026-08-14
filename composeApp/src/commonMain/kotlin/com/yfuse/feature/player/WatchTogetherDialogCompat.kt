package com.yfuse.feature.player

import androidx.compose.runtime.Composable
import com.yfuse.core.sync.WatchControlMode
import com.yfuse.core.sync.WatchParticipant

/**
 * Keeps the existing player-chrome call site source-compatible while the room playlist is
 * introduced independently. Playlist jumps are handed to the active gated player through a
 * one-shot media-key request, so the 100k-line-ish chrome file does not need a mechanical rewrite.
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
        onLeave = {
            WatchPlaylistPlaybackRequest.clear()
            onLeave()
        },
        onRequestControl = onRequestControl,
        onSetControlMode = onSetControlMode,
        onSetModerator = onSetModerator,
        onKickParticipant = onKickParticipant,
        onPlaylistPlay = WatchPlaylistPlaybackRequest::request,
        onDismiss = onDismiss,
    )
}
