package com.yfuse.feature.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/** Releasing a stand-in is a UI decision; it must never fabricate rendering/health evidence. */
@Composable
internal fun rememberSenPlayerPictureCoverExpired(
    engine: VideoEngine,
    state: PlaybackState,
    waitingForPicture: Boolean,
): Boolean {
    val generation = state.diagnostics.outputEvidenceGeneration
    var expired by remember(engine, state.currentIndex, generation) { mutableStateOf(false) }
    LaunchedEffect(engine, state.currentIndex, generation, waitingForPicture) {
        if (engine is SenPlayerVideoEngine && waitingForPicture) {
            delay(SENPLAYER_PICTURE_COVER_MS)
            expired = true
        }
    }
    return engine is SenPlayerVideoEngine && waitingForPicture && expired
}
