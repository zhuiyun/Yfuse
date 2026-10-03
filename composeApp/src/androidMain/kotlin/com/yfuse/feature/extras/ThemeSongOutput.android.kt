package com.yfuse.feature.extras

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.yfuse.core.logging.AppLog
import com.yfuse.core.model.ThemeSong
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal actual fun ThemeSongOutput(song: ThemeSong?) {
    val context = LocalContext.current.applicationContext
    val owner = remember { Any() }
    // Keyed by the file: a show's episodes inherit one theme, and it carries on from page to page.
    LaunchedEffect(song?.streamUrl) {
        if (song != null) ThemeSongPlayer.play(context, owner, song) else ThemeSongPlayer.fadeOut(owner)
    }
    DisposableEffect(owner) {
        onDispose { ThemeSongPlayer.fadeOut(owner) }
    }
}

internal actual fun silenceThemeSong() = ThemeSongPlayer.stop()

/**
 * The app's one theme song, on the platform's own MediaPlayer: a short audio file needs no more,
 * and it is there in every build, the ones without ExoPlayer included.
 *
 * Main thread only — Compose effects call it, and the player's callbacks arrive there.
 */
private object ThemeSongPlayer {
    private val scope = MainScope()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var song: ThemeSong? = null
    private var owner: Any? = null
    private var audioManager: AudioManager? = null
    private var focus: AudioFocusRequest? = null
    private var fade: Job? = null
    private var volume = 0f

    /** Prepared and playing: before that, volume changes are not the player's to take. */
    private var started = false

    fun play(
        context: Context,
        owner: Any,
        next: ThemeSong,
    ) {
        if (player != null && song?.streamUrl == next.streamUrl) {
            // Another page of the same show: the theme carries on and changes hands.
            this.owner = owner
            if (started) fadeTo(TARGET_VOLUME, FADE_IN_MS)
            return
        }
        stop()
        val audio = context.getSystemService(AudioManager::class.java) ?: return
        if (!mayStart(audio)) return
        val media = MediaPlayer()
        player = media
        song = next
        this.owner = owner
        audioManager = audio
        volume = 0f
        media.setAudioAttributes(
            AudioAttributes
                .Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
        )
        media.setVolume(0f, 0f)
        media.setOnPreparedListener { prepared ->
            if (player !== prepared) return@setOnPreparedListener
            if (!requestFocus(audio)) {
                stop()
                return@setOnPreparedListener
            }
            prepared.start()
            started = true
            fadeTo(TARGET_VOLUME, FADE_IN_MS)
        }
        media.setOnCompletionListener { done -> if (player === done) stop() }
        media.setOnErrorListener { failed, what, extra ->
            if (player === failed) {
                report(next, "what=$what extra=$extra")
                stop()
            }
            true
        }
        runCatching {
            media.setDataSource(next.streamUrl)
            media.prepareAsync()
        }.onFailure {
            report(next, it::class.simpleName.orEmpty())
            stop()
        }
    }

    /** Fades out what [owner] started; a song another page has taken since is not its to end. */
    fun fadeOut(owner: Any) {
        if (this.owner !== owner || player == null) return
        // Nothing heard yet, so nothing to fade.
        if (!started) return stop()
        fadeTo(0f, FADE_OUT_MS) { stop() }
    }

    fun stop() {
        fade?.cancel()
        fade = null
        player?.release()
        player = null
        song = null
        owner = null
        started = false
        volume = 0f
        focus?.let { request -> audioManager?.abandonAudioFocusRequest(request) }
        focus = null
    }

    /**
     * Sound nobody asked for, so it stays out of the way: not over a muted or silenced device, and
     * never over music another app is already playing.
     */
    private fun mayStart(audio: AudioManager): Boolean =
        !audio.isStreamMute(AudioManager.STREAM_MUSIC) &&
            audio.getStreamVolume(AudioManager.STREAM_MUSIC) > 0 &&
            audio.ringerMode == AudioManager.RINGER_MODE_NORMAL &&
            !audio.isMusicActive

    private fun requestFocus(audio: AudioManager): Boolean {
        val request =
            AudioFocusRequest
                .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    AudioAttributes
                        .Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                ).setOnAudioFocusChangeListener(
                    { change ->
                        // Any loss, a duck included: a call, an alarm or another app's audio wins.
                        if (change < 0) stop()
                    },
                    mainHandler,
                ).build()
        val granted = audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (granted) focus = request
        return granted
    }

    private fun fadeTo(
        target: Float,
        durationMs: Long,
        then: (() -> Unit)? = null,
    ) {
        fade?.cancel()
        val media = player ?: return
        val from = volume
        val steps = (durationMs / FADE_STEP_MS).coerceAtLeast(1L)
        fade =
            scope.launch {
                for (step in 1..steps) {
                    delay(FADE_STEP_MS)
                    volume = from + (target - from) * step / steps
                    runCatching { media.setVolume(volume, volume) }
                }
                then?.invoke()
            }
    }

    private fun report(
        failed: ThemeSong,
        reason: String,
    ) {
        AppLog.warning(
            category = "detail.theme",
            event = "theme_song_failed",
            message = "Theme song could not be played; the page stays silent",
            attributes = mapOf("serverId" to failed.serverId, "reason" to reason),
        )
    }
}

/** 轻声: under whatever the viewer turns to next, never over it. */
private const val TARGET_VOLUME = 0.35f
private const val FADE_IN_MS = 1_500L
private const val FADE_OUT_MS = 800L
private const val FADE_STEP_MS = 50L
