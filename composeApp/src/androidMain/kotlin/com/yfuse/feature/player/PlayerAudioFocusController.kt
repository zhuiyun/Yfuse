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
    private val isPlaying: () -> Boolean,
    private val onPause: () -> Unit,
    private val onResume: () -> Unit,
) {
    private var request: AudioFocusRequest? = null
    private var hasFocus = false
    private var resumeAfterTransientLoss = false

    private val listener =
        AudioManager.OnAudioFocusChangeListener { change ->
            when (change) {
                AudioManager.AUDIOFOCUS_GAIN -> {
                    hasFocus = true
                    if (resumeAfterTransientLoss) {
                        resumeAfterTransientLoss = false
                        // Audio focus is local, so resuming must not pass through the room gate.
                        onResume()
                    }
                    AppLog.info(
                        category = "player.audio",
                        event = "focus_gained",
                        message = "Playback regained audio focus",
                    )
                }

                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    resumeAfterTransientLoss = isPlaying()
                    hasFocus = false
                    onPause()
                    AppLog.info(
                        category = "player.audio",
                        event = "focus_lost_transient",
                        message = "Playback paused for a transient audio focus loss",
                    )
                }

                AudioManager.AUDIOFOCUS_LOSS -> {
                    resumeAfterTransientLoss = false
                    hasFocus = false
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
        if (hasFocus) return true
        val focusRequest =
            request ?: AudioFocusRequest
                .Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes
                        .Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build(),
                ).setOnAudioFocusChangeListener(listener, Handler(Looper.getMainLooper()))
                .build()
                .also { request = it }
        val result = audioManager.requestAudioFocus(focusRequest)
        hasFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (hasFocus) {
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
        return hasFocus
    }

    fun abandon() {
        if (!hasFocus && request == null) return
        request?.let(audioManager::abandonAudioFocusRequest)
        request = null
        hasFocus = false
        resumeAfterTransientLoss = false
    }
}
