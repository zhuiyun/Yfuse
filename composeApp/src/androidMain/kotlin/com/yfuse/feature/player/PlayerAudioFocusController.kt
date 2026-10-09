package com.yfuse.feature.player

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import com.yfuse.core.logging.AppLog

/** Owns Android audio-focus state independently from the activity and playback UI. */
internal class PlayerAudioFocusController(
    private val audioManager: AudioManager,
    private val playbackRequested: () -> Boolean,
    private val canResume: () -> Boolean,
    private val onPause: () -> Unit,
    private val onResume: () -> Unit,
) {
    private var request: AudioFocusRequest? = null
    private val state = PlayerAudioFocusState()

    private fun listener(requestId: Long) =
        AudioManager.OnAudioFocusChangeListener { change ->
            if (!state.isActive(requestId)) return@OnAudioFocusChangeListener
            when (change) {
                AudioManager.AUDIOFOCUS_GAIN -> {
                    if (state.gained(requestId, canResume())) {
                        // Audio focus is local, so resuming must not pass through the room gate.
                        onResume()
                    }
                    AppLog.info(
                        category = "player.audio",
                        event = "focus_gained",
                        message = "Playback regained audio focus",
                    )
                }

                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    // A navigation prompt or a notification chime: the platform lowers our
                    // volume for its duration and restores it, so the film keeps going.
                    // Pausing here is what made every turn-by-turn instruction stop the movie.
                    AppLog.info(
                        category = "player.audio",
                        event = "focus_ducked",
                        message = "Playback continues at reduced volume during a transient duck",
                    )
                }

                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    state.lost(requestId, transient = true, playbackRequested = playbackRequested())
                    onPause()
                    AppLog.info(
                        category = "player.audio",
                        event = "focus_lost_transient",
                        message = "Playback paused for a transient audio focus loss",
                    )
                }

                AudioManager.AUDIOFOCUS_LOSS -> {
                    state.lost(requestId, transient = false, playbackRequested = playbackRequested())
                    onPause()
                    AppLog.info(
                        category = "player.audio",
                        event = "focus_lost",
                        message = "Playback paused after losing audio focus",
                    )
                }
            }
        }

    fun ensure(): Boolean {
        if (state.hasFocus) return true
        val focusRequest =
            request ?: AudioFocusRequest
                .Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes
                        .Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build(),
                ).setWillPauseWhenDucked(false)
                .setOnAudioFocusChangeListener(listener(state.beginRequest()), Handler(Looper.getMainLooper()))
                .build()
                .also { request = it }
        val result = audioManager.requestAudioFocus(focusRequest)
        state.requested(result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        if (state.hasFocus) {
            AppLog.info(
                category = "player.audio",
                event = "focus_granted",
                message = "Playback audio focus was granted",
            )
        } else {
            AppLog.warning(
                category = "player.audio",
                event = "focus_denied",
                message = "Playback audio focus request was denied",
                attributes = mapOf("result" to result.toString()),
            )
        }
        return state.hasFocus
    }

    fun cancelResume() = state.cancelResume()

    fun abandon() {
        // Invalidate first: Android may already have queued a callback for the old listener.
        state.abandon()
        val abandoned = request
        request = null
        abandoned?.let(audioManager::abandonAudioFocusRequest)
    }
}
