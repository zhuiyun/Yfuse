package com.yfuse.feature.player

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Activity
import android.app.PictureInPictureParams
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.database.ContentObserver
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState as PlatformPlaybackState
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Rational
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.core.content.ContextCompat
import com.yfuse.core.data.DanmakuBinding
import com.yfuse.core.data.DanmakuComment
import com.yfuse.core.data.DanmakuFilter
import com.yfuse.core.data.DanmakuDisplayArea
import com.yfuse.core.data.DanmakuFontSize
import com.yfuse.core.data.DanmakuMedia
import com.yfuse.core.data.DanmakuOpacity
import com.yfuse.core.data.DanmakuPreferences
import com.yfuse.core.data.DanmakuRepository
import com.yfuse.core.data.DanmakuSpeed
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.PlaybackRecoveryStore
import com.yfuse.core.data.PlaybackTrackRequest
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.data.SkipMode
import com.yfuse.core.data.SkipSegmentPreferences
import com.yfuse.core.data.SkipTimes
import com.yfuse.core.data.ThemePreferences
import com.yfuse.core.data.UserAgentPreferences
import com.yfuse.core.data.WatchTogetherPreferences
import com.yfuse.core.data.activeOr
import com.yfuse.core.data.danmakuBindingKey
import com.yfuse.core.cast.CastManager
import com.yfuse.core.designsystem.AccentColor
import com.yfuse.core.designsystem.YfuseTheme
import com.yfuse.core.logging.AppLog
import com.yfuse.core.model.DecoderMode
import com.yfuse.core.model.PlaybackQuality
import com.yfuse.core.model.PlaybackSegment
import com.yfuse.core.model.PlaybackSegmentType
import com.yfuse.core.model.PlayerEngine
import com.yfuse.core.network.EmbyStream
import com.yfuse.core.sync.WatchTogetherClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext
import kotlin.math.roundToInt
import kotlin.time.TimeSource

/**
 * Fullscreen playback lives in its own activity so landscape is declared in the
 * manifest rather than forced at runtime (which misbehaves on some devices).
 */
class PlayerActivity : ComponentActivity() {

    companion object {
        private const val NOTIFICATION_CHANNEL = "yfuse_playback"
        private const val NOTIFICATION_ID = 2407
        private const val NOTIFICATION_PERMISSION_REQUEST = 2408
        private const val ACTION_PREVIOUS = "com.yfuse.player.PREVIOUS"
        private const val ACTION_PLAY_PAUSE = "com.yfuse.player.PLAY_PAUSE"
        private const val ACTION_NEXT = "com.yfuse.player.NEXT"
        private const val EXTRA_IDS = "yfuse.ids"
        private const val EXTRA_URLS = "yfuse.urls"
        private const val EXTRA_TRANSCODE = "yfuse.transcodeUrls"
        private const val EXTRA_FALLBACK_TRANSCODE = "yfuse.fallbackTranscodeUrls"
        private const val EXTRA_TITLES = "yfuse.titles"
        private const val EXTRA_SERVER_IDS = "yfuse.serverIds"
        private const val EXTRA_SEGMENTS = "yfuse.playbackSegments"
        private const val EXTRA_WATCH_KEYS = "yfuse.watchKeys"

        /** `|`-joined per item; see `PlayerMediaItem.matchKeys`. */
        private const val EXTRA_WATCH_MATCH_KEYS = "yfuse.watchMatchKeys"
        private const val EXTRA_SEASONS = "yfuse.seasonNumbers"
        private const val EXTRA_EPISODES = "yfuse.episodeNumbers"
        private const val EXTRA_VERSIONS = "yfuse.versions"
        private const val EXTRA_SERIES_KEYS = "yfuse.seriesIds"
        private const val EXTRA_SERIES_NAMES = "yfuse.seriesNames"
        private const val EXTRA_INDEX = "yfuse.index"
        private const val EXTRA_POSITION = "yfuse.positionMs"
        private const val EXTRA_ENGINE = "yfuse.engine"
        private const val EXTRA_DECODER = "yfuse.decoder"
        private const val EXTRA_AUTO_NEXT = "yfuse.autoNext"
        private const val EXTRA_QUALITY = "yfuse.quality"

        fun intent(
            context: Context,
            items: List<PlayerMediaItem>,
            startIndex: Int,
            startPositionMs: Long,
            engine: PlayerEngine,
            decoder: DecoderMode,
            autoNext: Boolean,
            quality: PlaybackQuality,
        ): Intent = Intent(context, PlayerActivity::class.java).apply {
            putExtra(EXTRA_IDS, items.map { it.id }.toTypedArray())
            putExtra(EXTRA_URLS, items.map { it.url }.toTypedArray())
            putExtra(EXTRA_TRANSCODE, items.map { it.transcodeUrl }.toTypedArray())
            putExtra(EXTRA_FALLBACK_TRANSCODE, items.map { it.fallbackTranscodeUrl }.toTypedArray())
            putExtra(EXTRA_TITLES, items.map { it.title }.toTypedArray())
            putExtra(EXTRA_SERVER_IDS, items.map { it.serverId.orEmpty() }.toTypedArray())
            putExtra(EXTRA_SEGMENTS, items.map(::encodePlaybackSegments).toTypedArray())
            putExtra(EXTRA_WATCH_KEYS, items.map { it.watchKey }.toTypedArray())
            putExtra(
                EXTRA_WATCH_MATCH_KEYS,
                items.map { it.matchKeys.joinToString(MATCH_KEY_SEPARATOR.toString()) }
                    .toTypedArray(),
            )
            putExtra(EXTRA_SEASONS, items.map { it.seasonNumber ?: -1 }.toIntArray())
            putExtra(EXTRA_EPISODES, items.map { it.episodeNumber ?: -1 }.toIntArray())
            putExtra(EXTRA_VERSIONS, items.map(::encodeVersions).toTypedArray())
            putExtra(EXTRA_SERIES_KEYS, items.map { it.seriesId.orEmpty() }.toTypedArray())
            putExtra(EXTRA_SERIES_NAMES, items.map { it.seriesName.orEmpty() }.toTypedArray())
            putExtra(EXTRA_INDEX, startIndex)
            putExtra(EXTRA_POSITION, startPositionMs)
            putExtra(EXTRA_ENGINE, engine.name)
            putExtra(EXTRA_DECODER, decoder.name)
            putExtra(EXTRA_AUTO_NEXT, autoNext)
            putExtra(EXTRA_QUALITY, quality.name)
        }
    }

    private var activeEngine: VideoEngine? = null
    private var playbackGate: WatchGatedPlayback? = null
    private var activeState = PlaybackState()
    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false
    private var resumeAfterTransientFocusLoss = false
    private var sessionTitles: List<String> = emptyList()
    private val pictureInPicture = MutableStateFlow(false)
    private lateinit var mediaSession: MediaSession
    private lateinit var notificationManager: NotificationManager
    private var mediaReceiverRegistered = false
    private var videoBounds: Rect? = null
    private var pipWasVisible = false
    private var stopRequested = false
    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasAudioFocus = true
                if (resumeAfterTransientFocusLoss) {
                    resumeAfterTransientFocusLoss = false
                    playbackGate?.play()
                }
                AppLog.info(
                    category = "player.audio",
                    event = "focus_gained",
                    message = "Playback regained audio focus",
                )
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                resumeAfterTransientFocusLoss = activeState.playing
                hasAudioFocus = false
                playbackGate?.pause()
                AppLog.info(
                    category = "player.audio",
                    event = "focus_lost_transient",
                    message = "Playback paused for a transient audio focus loss",
                )
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                resumeAfterTransientFocusLoss = false
                hasAudioFocus = false
                playbackGate?.pause()
                AppLog.info(
                    category = "player.audio",
                    event = "focus_lost",
                    message = "Playback paused after losing audio focus",
                )
            }
        }
    }
    private val mediaActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_PREVIOUS -> playbackGate?.selectPrevious()
                ACTION_PLAY_PAUSE -> togglePlaybackWithFocus()
                ACTION_NEXT -> playbackGate?.selectNext()
            }
        }
    }

    /**
     * Bumped by each volume key press, so the player can put its own slider on screen.
     *
     * A counter rather than a level: the level is already tracked in composition, and what
     * a key press adds is only "show it now". Two presses at the same volume — at the
     * ceiling, or on the mute floor — still have to keep the slider up, which a level alone
     * could not express.
     */
    private val volumeKeyPresses = MutableStateFlow(0L)

    /**
     * Handles the volume rocker so the player can answer it with its own slider.
     *
     * Left alone, Android puts its own panel over the picture — sized and placed for the
     * home screen, unaware there is a film behind it. The rocker still does exactly what it
     * did (one step of `STREAM_MUSIC` per press, held presses repeating); only the thing
     * that appears in response changes.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN -> {
            audioManager.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                    AudioManager.ADJUST_RAISE
                } else {
                    AudioManager.ADJUST_LOWER
                },
                // No flags: the adjustment happens, the system panel does not.
                0,
            )
            volumeKeyPresses.value++
            true
        }

        else -> super.onKeyDown(keyCode, event)
    }

    /** Consumed alongside the down event, or the system panel appears on release. */
    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN -> true
        else -> super.onKeyUp(keyCode, event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // The player is watched, not touched — nothing here should let the screen time out
        // mid-film. The per-view keepScreenOn each engine sets only covers its own surface;
        // this covers the window, including while buffering or paused on a still frame.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Without this the hardware keys adjust whichever stream the system last considered
        // active — the ring volume, most often — so a user turning the volume up on a film
        // that has gone quiet changes nothing they can hear.
        volumeControlStream = AudioManager.STREAM_MUSIC
        audioManager = getSystemService(AudioManager::class.java)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }

        val initialEngine = intent.getStringExtra(EXTRA_ENGINE)
            ?.let { name -> PlayerEngine.entries.firstOrNull { it.name == name } }
            ?: PlayerEngine.Exo
        val decoderMode = intent.getStringExtra(EXTRA_DECODER)
            ?.let { name -> DecoderMode.entries.firstOrNull { it.name == name } }
            ?: DecoderMode.Hardware
        val autoNext = intent.getBooleanExtra(EXTRA_AUTO_NEXT, true)
        val quality = intent.getStringExtra(EXTRA_QUALITY)
            ?.let { name -> PlaybackQuality.entries.firstOrNull { it.name == name } }
            ?: PlaybackQuality.Auto

        val ids = intent.getStringArrayExtra(EXTRA_IDS).orEmpty()
        val urls = intent.getStringArrayExtra(EXTRA_URLS).orEmpty()
        val transcodeUrls = intent.getStringArrayExtra(EXTRA_TRANSCODE).orEmpty()
        val fallbackTranscodeUrls = intent.getStringArrayExtra(EXTRA_FALLBACK_TRANSCODE).orEmpty()
        val titles = intent.getStringArrayExtra(EXTRA_TITLES).orEmpty()
        val serverIds = intent.getStringArrayExtra(EXTRA_SERVER_IDS).orEmpty()
        val segmentRows = intent.getStringArrayExtra(EXTRA_SEGMENTS).orEmpty()
        val watchKeys = intent.getStringArrayExtra(EXTRA_WATCH_KEYS).orEmpty()
        val watchMatchKeys = intent.getStringArrayExtra(EXTRA_WATCH_MATCH_KEYS).orEmpty()
        val seasonNumbers = intent.getIntArrayExtra(EXTRA_SEASONS) ?: intArrayOf()
        val episodeNumbers = intent.getIntArrayExtra(EXTRA_EPISODES) ?: intArrayOf()
        val versionRows = intent.getStringArrayExtra(EXTRA_VERSIONS).orEmpty()
        val seriesIds = intent.getStringArrayExtra(EXTRA_SERIES_KEYS).orEmpty()
        val seriesNames = intent.getStringArrayExtra(EXTRA_SERIES_NAMES).orEmpty()
        val items = urls.mapIndexed { index, url ->
            val watchKey = watchKeys.getOrElse(index) {
                "emby:${ids.getOrElse(index) { index.toString() }}"
            }
            PlayerMediaItem(
                id = ids.getOrElse(index) { index.toString() },
                url = url,
                transcodeUrl = EmbyStream.withQuality(
                    transcodeUrls.getOrElse(index) { "" },
                    quality,
                ),
                title = titles.getOrElse(index) { "" },
                fallbackTranscodeUrl = EmbyStream.withQuality(
                    fallbackTranscodeUrls.getOrElse(index) { "" },
                    quality,
                ),
                serverId = serverIds.getOrNull(index)?.ifBlank { null },
                playbackSegments = decodePlaybackSegments(segmentRows.getOrElse(index) { "" }),
                seasonNumber = seasonNumbers.getOrNull(index)?.takeIf { it >= 0 },
                episodeNumber = episodeNumbers.getOrNull(index)?.takeIf { it >= 0 },
                seriesId = seriesIds.getOrNull(index)?.ifBlank { null },
                seriesName = seriesNames.getOrNull(index)?.ifBlank { null },
                watchKey = watchKey,
                matchKeys = watchMatchKeys.getOrNull(index)
                    ?.split(MATCH_KEY_SEPARATOR)
                    ?.filter { it.isNotBlank() }
                    ?.takeIf { it.isNotEmpty() }
                    ?: listOf(watchKey),
                versions = decodeVersions(versionRows.getOrElse(index) { "" }),
            )
        }
        pictureInPicture.value = isInPictureInPictureMode
        pipWasVisible = isInPictureInPictureMode
        sessionTitles = items.map { it.title }
        createMediaSession()
        createPlaybackNotificationChannel()
        registerMediaActionReceiver()
        requestNotificationPermissionIfNeeded()

        val koin = GlobalContext.get()
        val preferences = runCatching { koin.get<ThemePreferences>() }
            .onFailure {
                AppLog.warning(
                    category = "feature.player",
                    event = "activity_preferences_unavailable",
                    message = "Player activity could not load theme preferences",
                    throwable = it,
                )
            }
            .getOrNull()
        val danmakuPreferences = koin.get<DanmakuPreferences>()
        val skipSegmentPreferences = koin.get<SkipSegmentPreferences>()
        val danmakuRepository = koin.get<DanmakuRepository>()
        val playbackRecovery = koin.get<PlaybackRecoveryStore>()
        val customUserAgent = koin.get<UserAgentPreferences>().userAgent.value
        val watchTogether = koin.get<WatchTogetherClient>()
        val watchTogetherPreferences = koin.get<WatchTogetherPreferences>()
        val playbackController = WatchGatedPlayback(
            watchTogether = watchTogether,
            items = { items },
            engine = { activeEngine },
            onLocked = {
                runOnUiThread {
                    Toast.makeText(this, "当前由房主控制播放", Toast.LENGTH_SHORT).show()
                }
            },
        )
        playbackGate = playbackController
        ActivePlayback.bind(
            toggle = ::togglePlaybackWithFocus,
            open = {
                startActivity(
                    Intent(this, PlayerActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                )
            },
            close = ::stopPlaybackAndFinish,
        )
        ensureAudioFocus()
        val accent = preferences?.accent?.value ?: AccentColor.Blue
        val playbackSink = runCatching {
            val registry = koin.get<ServerRegistry>()
            val selectedServerId = items.getOrNull(
                intent.getIntExtra(EXTRA_INDEX, 0),
            )?.serverId
            val server = selectedServerId?.let(registry::serverById) ?: registry.defaultServer
            val repo = koin.get<EmbyRepository>()
            server?.let { EmbyPlaybackEventSink(repo, it) }
        }.onFailure {
            AppLog.warning(
                category = "feature.player",
                event = "playback_reporting_unavailable",
                message = "Server playback reporting could not be initialized",
                throwable = it,
            )
        }.getOrNull()

        setContent {
            val inPictureInPicture by pictureInPicture.collectAsState()
            // Always the dark palette: the controls float over the picture.
            YfuseTheme(dark = true, accent = accent) {
                PlayerRoot(
                    items = items,
                    startIndex = intent.getIntExtra(EXTRA_INDEX, 0),
                    startPositionMs = intent.getLongExtra(EXTRA_POSITION, 0L),
                    initialEngine = initialEngine,
                    decoderMode = decoderMode,
                    autoNext = autoNext,
                    quality = quality,
                    inPictureInPicture = inPictureInPicture,
                    playbackSink = playbackSink,
                    danmakuPreferences = danmakuPreferences,
                    skipSegmentPreferences = skipSegmentPreferences,
                    volumeKeyPresses = volumeKeyPresses,
                    danmakuRepository = danmakuRepository,
                    playbackRecovery = playbackRecovery,
                    customUserAgent = customUserAgent,
                    watchTogether = watchTogether,
                    watchTogetherPreferences = watchTogetherPreferences,
                    playbackGate = playbackController,
                    onEngineAttached = { engine -> activeEngine = engine },
                    onEngineDetached = { engine ->
                        if (activeEngine === engine) activeEngine = null
                    },
                    onPlaybackState = { state ->
                        activeState = state
                        ActivePlayback.update(
                            sessionTitles.getOrNull(state.currentIndex).orEmpty(),
                            state,
                        )
                        updateMediaSession(state)
                        updatePictureInPictureParams()
                        if (state.playing) {
                            ContextCompat.startForegroundService(
                                this,
                                Intent(this, PlaybackKeepAliveService::class.java),
                            )
                        } else {
                            stopService(Intent(this, PlaybackKeepAliveService::class.java))
                        }
                    },
                    onVideoBounds = { bounds ->
                        videoBounds = bounds
                        updatePictureInPictureParams()
                    },
                    onBack = ::returnToMain,
                )
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S &&
            activeState.playing &&
            !isFinishing
        ) {
            enterPictureInPictureMode(
                PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))
                    .apply { videoBounds?.let(::setSourceRectHint) }
                    .build(),
            )
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pictureInPicture.value = isInPictureInPictureMode
        if (isInPictureInPictureMode) pipWasVisible = true
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Only an expanded player regains a focused full-size window. Closing
        // PiP never does, so keep the marker for onStop to release playback.
        if (hasFocus && !isInPictureInPictureMode) pipWasVisible = false
    }

    override fun onStop() {
        super.onStop()
        // Android can keep a closed PiP activity stopped but alive. Explicitly
        // tear down its engine so audio cannot continue invisibly.
        if (pipWasVisible && !isChangingConfigurations) {
            stopPlaybackAndFinish()
        }
    }

    override fun onDestroy() {
        ActivePlayback.clear()
        stopService(Intent(this, PlaybackKeepAliveService::class.java))
        abandonAudioFocus()
        notificationManager.cancel(NOTIFICATION_ID)
        if (mediaReceiverRegistered) {
            runCatching { unregisterReceiver(mediaActionReceiver) }
            mediaReceiverRegistered = false
        }
        mediaSession.isActive = false
        mediaSession.release()
        super.onDestroy()
        playbackGate = null
    }

    private fun returnToMain() {
        packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
            startActivity(
                launch.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
                ),
            )
        }
    }

    private fun stopPlaybackAndFinish() {
        if (stopRequested) return
        stopRequested = true
        activeEngine?.release()
        activeEngine = null
        abandonAudioFocus()
        ActivePlayback.clear()
        stopService(Intent(this, PlaybackKeepAliveService::class.java))
        finishAndRemoveTask()
    }

    private fun createMediaSession() {
        mediaSession = MediaSession(this, "YfusePlayer").apply {
            setCallback(
                object : MediaSession.Callback() {
                    override fun onPlay() {
                        if (ensureAudioFocus()) playbackGate?.play()
                    }

                    override fun onPause() {
                        playbackGate?.pause()
                    }

                    override fun onSeekTo(pos: Long) {
                        playbackGate?.seekTo(pos)
                    }

                    override fun onSkipToNext() {
                        playbackGate?.selectNext()
                    }

                    override fun onSkipToPrevious() {
                        playbackGate?.selectPrevious()
                    }
                },
            )
            isActive = true
        }
    }

    private fun createPlaybackNotificationChannel() {
        notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL,
                "播放控制",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "正在播放的影视与控制按钮"
                setSound(null, null)
            },
        )
    }

    private fun registerMediaActionReceiver() {
        val filter = IntentFilter().apply {
            addAction(ACTION_PREVIOUS)
            addAction(ACTION_PLAY_PAUSE)
            addAction(ACTION_NEXT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(mediaActionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(mediaActionReceiver, filter)
        }
        mediaReceiverRegistered = true
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                NOTIFICATION_PERMISSION_REQUEST,
            )
        }
    }

    private fun updatePictureInPictureParams() {
        val params = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
            .apply {
                videoBounds?.let(::setSourceRectHint)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setAutoEnterEnabled(activeState.playing)
                }
            }
            .build()
        setPictureInPictureParams(params)
    }

    private fun updateMediaSession(state: PlaybackState) {
        if (state.playing && !ensureAudioFocus()) {
            playbackGate?.pause()
        } else if (state.ended || state.error != null) {
            abandonAudioFocus()
        }
        val actions = PlatformPlaybackState.ACTION_PLAY or
            PlatformPlaybackState.ACTION_PAUSE or
            PlatformPlaybackState.ACTION_PLAY_PAUSE or
            PlatformPlaybackState.ACTION_SEEK_TO or
            PlatformPlaybackState.ACTION_SKIP_TO_NEXT or
            PlatformPlaybackState.ACTION_SKIP_TO_PREVIOUS
        val platformState = when {
            state.error != null -> PlatformPlaybackState.STATE_ERROR
            state.ended -> PlatformPlaybackState.STATE_STOPPED
            state.buffering -> PlatformPlaybackState.STATE_BUFFERING
            state.playing -> PlatformPlaybackState.STATE_PLAYING
            else -> PlatformPlaybackState.STATE_PAUSED
        }
        val builder = PlatformPlaybackState.Builder()
            .setActions(actions)
            .setState(platformState, state.positionMs, state.speed)
        state.error?.let(builder::setErrorMessage)
        mediaSession.setPlaybackState(builder.build())
        mediaSession.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, sessionTitles.getOrNull(state.currentIndex).orEmpty())
                .putLong(MediaMetadata.METADATA_KEY_DURATION, state.durationMs)
                .build(),
        )
        updatePlaybackNotification(state)
    }

    private fun togglePlaybackWithFocus() {
        if (activeState.playing) {
            playbackGate?.pause()
        } else if (ensureAudioFocus()) {
            playbackGate?.play()
        }
    }

    private fun ensureAudioFocus(): Boolean {
        if (hasAudioFocus) return true
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = audioFocusRequest ?: AudioFocusRequest.Builder(
                AudioManager.AUDIOFOCUS_GAIN,
            )
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build(),
                )
                .setOnAudioFocusChangeListener(
                    audioFocusChangeListener,
                    Handler(Looper.getMainLooper()),
                )
                .build()
                .also { audioFocusRequest = it }
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN,
            )
        }
        hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (hasAudioFocus) {
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
        return hasAudioFocus
    }

    private fun abandonAudioFocus() {
        if (!hasAudioFocus && audioFocusRequest == null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let(audioManager::abandonAudioFocusRequest)
            audioFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(audioFocusChangeListener)
        }
        hasAudioFocus = false
        resumeAfterTransientFocusLoss = false
    }

    private fun updatePlaybackNotification(state: PlaybackState) {
        val title = sessionTitles.getOrNull(state.currentIndex).orEmpty().ifBlank { "Yfuse" }
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(intent).setClass(this, PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val previousIntent = mediaPendingIntent(ACTION_PREVIOUS, 1)
        val playPauseIntent = mediaPendingIntent(ACTION_PLAY_PAUSE, 2)
        val nextIntent = mediaPendingIntent(ACTION_NEXT, 3)
        val playPauseIcon = if (state.playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val playPauseLabel = if (state.playing) "暂停" else "播放"

        val notification = Notification.Builder(this, NOTIFICATION_CHANNEL)
            .setSmallIcon(playPauseIcon)
            .setContentTitle(title)
            .setContentText(
                when {
                    state.error != null -> "播放失败，可返回播放器重试"
                    state.ended -> "播放完成"
                    state.buffering -> "正在缓冲"
                    state.playing -> "正在播放"
                    else -> "已暂停"
                },
            )
            .setContentIntent(contentIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(state.playing)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_media_previous),
                    "上一集",
                    previousIntent,
                ).build(),
            )
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, playPauseIcon),
                    playPauseLabel,
                    playPauseIntent,
                ).build(),
            )
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_media_next),
                    "下一集",
                    nextIntent,
                ).build(),
            )
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2),
            )
            .build()

        runCatching { notificationManager.notify(NOTIFICATION_ID, notification) }
    }

    private fun mediaPendingIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            this,
            requestCode,
            Intent(action).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}

/** Separator for the `|`-joined `matchKeys` row; absent from provider ids and Emby ids. */
private const val MATCH_KEY_SEPARATOR = '|'

/**
 * Versions across the intent boundary, as JSON.
 *
 * This used to be six fields joined by an ASCII unit separator, positionally decoded. It
 * broke silently the first time a field was added to [PlayerMediaVersion] and nobody
 * remembered to touch both ends: the encoder wrote six, the decoder read six, and the
 * container and 杜比 flags added later arrived as their defaults — so the badge the player
 * was supposed to show never appeared and nothing anywhere reported a problem.
 *
 * A serializer cannot make that mistake. Unknown keys are ignored and missing ones take
 * their defaults, so the two ends can also be different versions of the app mid-update.
 */
private val versionsJson = Json { ignoreUnknownKeys = true }
private val versionsSerializer = ListSerializer(PlayerMediaVersion.serializer())

private fun encodeVersions(item: PlayerMediaItem): String =
    if (item.versions.isEmpty()) {
        ""
    } else {
        versionsJson.encodeToString(versionsSerializer, item.versions)
    }

private fun decodeVersions(raw: String): List<PlayerMediaVersion> {
    if (raw.isEmpty()) return emptyList()
    return runCatching {
        versionsJson.decodeFromString(versionsSerializer, raw)
    }.onFailure {
        AppLog.warning(
            category = "feature.player",
            event = "versions_undecodable",
            message = "Playback versions could not be read from the launch intent",
            throwable = it,
        )
    }.getOrDefault(emptyList())
}

private fun encodePlaybackSegments(item: PlayerMediaItem): String =
    item.playbackSegments.joinToString(";") { segment ->
        "${segment.type.name},${segment.startMs},${segment.endMs ?: ""}"
    }

private fun decodePlaybackSegments(raw: String): List<PlaybackSegment> =
    raw.split(';').mapNotNull { row ->
        val fields = row.split(',', limit = 3)
        val type = fields.getOrNull(0)?.let { name ->
            PlaybackSegmentType.entries.firstOrNull { it.name == name }
        } ?: return@mapNotNull null
        val startMs = fields.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
        PlaybackSegment(
            type = type,
            startMs = startMs,
            endMs = fields.getOrNull(2)?.toLongOrNull(),
        )
    }

/** How often a guest re-checks its drift against the room's timeline. */
private const val GUEST_RECONCILE_TICK_MS = 1_000L

/** Below this, drift is imperceptible and left alone. */
private const val NUDGE_THRESHOLD_MS = 50L

/** Above this, nudging would take too long to feel right — jump instead. */
private const val HARD_SEEK_THRESHOLD_MS = 2_000L

/**
 * How long a guest waits for its own correction to take effect before correcting again.
 *
 * Long enough for a seek into an unbuffered part of a remote file — several seconds on a
 * slow link — and short enough that a correction the engine silently dropped doesn't strand
 * the guest out of sync for the rest of the film.
 */
private const val CORRECTION_SETTLE_TIMEOUT_MS = 8_000L

/** Speed offset used to close a nudge-range gap without an audible/visible jump. */
private const val NUDGE_FRACTION = 0.02f

/** Avoids reissuing `setSpeed` every tick for a rate that hasn't materially changed. */
private const val RATE_EPSILON = 0.001f

/**
 * How long an automatic skip is announced before it happens.
 *
 * Long enough to read the pill and reach it, short enough that someone who wanted the skip
 * isn't left watching the thing they asked to have skipped.
 */
private const val AUTO_SKIP_COUNTDOWN_SECONDS = 5

/**
 * Owns the live engine and the shared control layer. Switching engines reads
 * the outgoing engine's position first, so the replacement picks up where it
 * left off instead of restarting the entry.
 */
@OptIn(UnstableApi::class)
@Composable
private fun PlayerRoot(
    items: List<PlayerMediaItem>,
    startIndex: Int,
    startPositionMs: Long,
    initialEngine: PlayerEngine,
    decoderMode: DecoderMode,
    autoNext: Boolean,
    quality: PlaybackQuality,
    inPictureInPicture: Boolean,
    playbackSink: PlaybackEventSink?,
    danmakuPreferences: DanmakuPreferences,
    danmakuRepository: DanmakuRepository,
    skipSegmentPreferences: SkipSegmentPreferences,
    /** Ticks on every volume key press; drives the player's own volume slider. */
    volumeKeyPresses: StateFlow<Long>,
    playbackRecovery: PlaybackRecoveryStore,
    customUserAgent: String,
    watchTogether: WatchTogetherClient,
    watchTogetherPreferences: WatchTogetherPreferences,
    playbackGate: WatchGatedPlayback,
    onEngineAttached: (VideoEngine) -> Unit,
    onEngineDetached: (VideoEngine) -> Unit,
    onPlaybackState: (PlaybackState) -> Unit,
    onVideoBounds: (Rect) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    /**
     * Dolby Vision that only a Dolby decoder can render picks the engine that has one.
     *
     * ExoPlayer is the only one of the three that goes through Android's `MediaCodec` and
     * can therefore reach a device's Dolby Vision decoder at all; libmpv and MDK have no
     * RPU handling, so a profile 5 file decodes "successfully" into a magenta-and-green
     * picture and nothing in either pipeline reports an error to fall back on. Choosing
     * for the user beats letting them discover that.
     *
     * Only for the profiles that have no compatible base layer — profile 8 plays as HDR10
     * on any engine, which is a fine thing to leave to whichever they preferred.
     */
    val dolbyNeedsExo = remember(items, startIndex) {
        items.getOrNull(startIndex)?.activeVersion?.needsDolbyDecoder == true
    }
    var kind by remember {
        mutableStateOf(if (dolbyNeedsExo) PlayerEngine.Exo else initialEngine)
    }
    // Where a newly built engine should start: index + position, updated on
    // every handover so the switch is seamless.
    var resume by remember { mutableStateOf(startIndex to startPositionMs) }
    var filled by remember { mutableStateOf(false) }

    // Entry id -> chosen file, for titles the server holds more than one copy of. Switching
    // rebuilds the queue and restarts the engine at the same position, which is the same
    // handover an engine switch already performs — no engine needs to know about versions.
    var versionChoices by remember { mutableStateOf(emptyMap<String, String>()) }
    val activeItems = remember(items, versionChoices) {
        if (versionChoices.isEmpty()) {
            items
        } else {
            items.map { item -> item.withVersion(versionChoices[item.id]) }
        }
    }

    val engine: VideoEngine = remember(kind, resume, activeItems) {
        when (kind) {
            PlayerEngine.Mdk -> MdkVideoEngine(
                items = activeItems,
                startIndex = resume.first,
                startPositionMs = resume.second,
                decoderMode = decoderMode,
                autoNext = autoNext,
                quality = quality,
                customUserAgent = customUserAgent,
                scope = scope,
            )
            PlayerEngine.Mpv -> MpvVideoEngine(
                context = context,
                items = activeItems,
                startIndex = resume.first,
                startPositionMs = resume.second,
                decoderMode = decoderMode,
                autoNext = autoNext,
                quality = quality,
                customUserAgent = customUserAgent,
            )
            else -> ExoVideoEngine(
                context = context,
                items = activeItems,
                startIndex = resume.first,
                startPositionMs = resume.second,
                scope = scope,
                decoderMode = decoderMode,
                autoNext = autoNext,
                quality = quality,
                customUserAgent = customUserAgent,
            )
        }
    }

    DisposableEffect(engine, kind) {
        AppLog.info(
            category = "player",
            event = "engine_attached",
            message = "Playback engine attached",
            attributes = mapOf(
                "engine" to kind.name,
                "implementation" to engine::class.java.name,
            ),
        )
        onEngineAttached(engine)
        onDispose {
            onEngineDetached(engine)
            engine.release()
            AppLog.info(
                category = "player",
                event = "engine_detached",
                message = "Playback engine detached",
                attributes = mapOf(
                    "engine" to kind.name,
                    "implementation" to engine::class.java.name,
                ),
            )
        }
    }

    val state by engine.state.collectAsState()
    // The same guard, for the cases the initial choice cannot cover: a queue that moves
    // into a Dolby-only episode, or someone picking libmpv by hand while one is playing.
    // The server's transcode is the only thing left that will put a correct picture up.
    LaunchedEffect(engine, kind, state.currentIndex, state.transcoding) {
        if (kind == PlayerEngine.Exo || state.transcoding) return@LaunchedEffect
        val needsDolby = activeItems.getOrNull(state.currentIndex)
            ?.activeVersion
            ?.needsDolbyDecoder == true
        if (!needsDolby) return@LaunchedEffect
        // The engine says whether it had anywhere to fall back to. When it did not, the
        // picture is going to be wrong and the log is the only place that will say why —
        // so it must not claim a switch that never happened.
        val switched = engine.switchToTranscode()
        AppLog.warning(
            category = "player",
            event = if (switched) "dolby_requires_transcode" else "dolby_undecodable",
            message = if (switched) {
                "Dolby Vision without a compatible base layer on a non-Dolby engine; " +
                    "switched to the server transcode"
            } else {
                "Dolby Vision without a compatible base layer and no transcode to fall " +
                    "back to; the picture will be wrong"
            },
            attributes = mapOf("engine" to kind.name),
        )
    }

    val watchState by watchTogether.state.collectAsState()
    val watchEndpoint by watchTogetherPreferences.endpoint.collectAsState()
    val danmakuSources by danmakuPreferences.sources.collectAsState()
    val danmakuActiveSourceId by danmakuPreferences.activeSourceId.collectAsState()
    val danmakuBindings by danmakuPreferences.bindings.collectAsState()
    val danmakuEnabled by danmakuPreferences.enabled.collectAsState()
    val danmakuArea by danmakuPreferences.displayArea.collectAsState()
    val danmakuFont by danmakuPreferences.fontSize.collectAsState()
    val danmakuSpeed by danmakuPreferences.speed.collectAsState()
    val danmakuOpacity by danmakuPreferences.opacity.collectAsState()
    val danmakuMerge by danmakuPreferences.mergeDuplicates.collectAsState()
    val danmakuBlocked by danmakuPreferences.blockedWords.collectAsState()
    val danmakuRecent by danmakuPreferences.recentSearches.collectAsState()
    var danmakuComments by remember { mutableStateOf(emptyList<DanmakuComment>()) }
    var danmakuLoading by remember { mutableStateOf(false) }
    var danmakuError by remember { mutableStateOf<String?>(null) }
    /** Which episode the comments on screen came from, for the 弹幕 panel to print. */
    var danmakuMatch by remember { mutableStateOf<String?>(null) }
    var danmakuSearch by remember { mutableStateOf(DanmakuSearchState()) }
    var danmakuSending by remember { mutableStateOf(false) }
    var danmakuSendError by remember { mutableStateOf<String?>(null) }
    /** Bumped by 重试, which is the only thing that re-runs a load nothing else changed. */
    var danmakuReloads by remember { mutableIntStateOf(0) }
    /** The episode the loaded comments came from — what 发送弹幕 posts to. */
    var danmakuEpisodeId by remember { mutableStateOf<String?>(null) }
    val danmakuSource = danmakuSources.activeOr(danmakuActiveSourceId)
    val currentItem = activeItems.getOrNull(state.currentIndex)
    // Read as state so editing this series' times mid-episode takes effect on the open
    // player rather than only on the next launch.
    val skipTimesBySeries by skipSegmentPreferences.bySeries.collectAsState()
    val skipMode by skipSegmentPreferences.skipMode.collectAsState()
    // id to name, for the readout's leading segment. Read from the registry rather than
    // carried on the queue: the name is a property of the server, not of the file.
    val serverNames = remember {
        GlobalContext.get().get<ServerRegistry>().data.value.servers.associate { it.id to it.serverName }
    }
    val skipTimes = currentItem?.seriesId?.let(skipTimesBySeries::get)
    // Keyed on the duration too: 片尾 is stored as a distance back from the end, so it
    // cannot be placed until the engine reports how long this entry is.
    val activeSegment = remember(currentItem, skipTimesBySeries, state.durationMs) {
        skipSegmentPreferences.applyTo(
            seriesId = currentItem?.seriesId,
            serverSegments = currentItem?.playbackSegments.orEmpty(),
            durationMs = state.durationMs,
        )
    }.firstOrNull { segment ->
        segment.contains(state.positionMs, state.durationMs)
    }
    val skipSegment: () -> Unit = {
        when (activeSegment?.type) {
            PlaybackSegmentType.Intro -> activeSegment.endMs?.let(playbackGate::seekTo)
            PlaybackSegmentType.Credits -> if (state.hasNext) {
                playbackGate.selectNext()
            } else {
                playbackGate.seekTo((state.durationMs - 500L).coerceAtLeast(0L))
            }
            null -> Unit
        }
    }

    // Automatic skipping, announced before it happens.
    //
    // Occurrences are identified by entry id plus segment type rather than by the segment
    // itself, so holding still inside an intro doesn't re-arm the countdown on every
    // position tick, while the next episode's intro is a different occurrence and does arm
    // again. [settledSkip] is the one this player has already dealt with — cancelled, or
    // skipped and then rewound back into. Either way the user has since chosen to be here,
    // and moving the playhead off it a second time would be taking the choice back.
    var settledSkip by remember { mutableStateOf<Pair<String, PlaybackSegmentType>?>(null) }
    var skipCountdownSeconds by remember { mutableStateOf<Int?>(null) }
    val skipOccurrence = activeSegment?.let { segment ->
        currentItem?.id?.let { it to segment.type }
    }
    // A guest's playback is the host's to move. Arming here would fire a refused seek —
    // and its "当前由房主控制播放" toast — at every opening in the season.
    val watchGuest = watchState.connected && !watchState.isHost
    val skipArmed = skipOccurrence != null &&
        skipMode == SkipMode.Auto &&
        !watchGuest &&
        skipOccurrence != settledSkip
    // The countdown outlives several position ticks, so it must fire against the playback
    // state as it is when it expires, not as it was when the effect was launched.
    val latestSkipSegment by rememberUpdatedState(skipSegment)
    LaunchedEffect(skipOccurrence, skipArmed) {
        if (!skipArmed) {
            skipCountdownSeconds = null
            return@LaunchedEffect
        }
        for (remaining in AUTO_SKIP_COUNTDOWN_SECONDS downTo 1) {
            skipCountdownSeconds = remaining
            delay(1_000L)
        }
        skipCountdownSeconds = null
        // Settled before the seek, so that rewinding into this same opening later gets the
        // manual pill rather than a second countdown.
        settledSkip = skipOccurrence
        latestSkipSegment()
    }
    // 详情页 picked a 音轨 / 字幕 before this opened; apply it once the engine has published
    // what the file actually holds. Consumed rather than remembered — see PlaybackTrackRequest.
    val trackRequest = remember { GlobalContext.get().get<PlaybackTrackRequest>() }
    LaunchedEffect(currentItem?.id, state.audioTracks.size, state.subtitleTracks.size) {
        if (state.audioTracks.isEmpty() && state.subtitleTracks.isEmpty()) return@LaunchedEffect
        val requested = trackRequest.consume(currentItem?.id) ?: return@LaunchedEffect
        requested.audioLanguage?.let { language ->
            state.audioTracks.matching(language)?.let(engine::selectAudioTrack)
        }
        when (val subtitle = requested.subtitleLanguage) {
            null -> Unit
            PlaybackTrackRequest.SUBTITLES_OFF -> engine.selectSubtitleTrack(EngineTrack.OFF)
            else -> state.subtitleTracks.matching(subtitle)?.let(engine::selectSubtitleTrack)
        }
    }

    // Keyed on the show and its coordinate rather than the library's item id, so a match
    // made on one server still holds on another — see danmakuBindingKey.
    val danmakuKey = currentItem?.let { item ->
        danmakuBindingKey(
            itemId = item.id,
            title = item.title,
            seriesName = item.seriesName,
            seasonNumber = item.seasonNumber,
            episodeNumber = item.episodeNumber,
        )
    }
    // A hand-picked match, if this entry has one and the source it names still exists.
    val danmakuBinding = danmakuKey
        ?.let { key ->
            // Read through the flow rather than the preference object so a fresh match
            // re-runs the loader below; `bindings` is what changes when one is written.
            danmakuBindings[key] ?: currentItem?.id?.let(danmakuBindings::get)
        }
        ?.takeIf { binding -> danmakuSources.any { it.id == binding.sourceId } }
    LaunchedEffect(
        currentItem?.id,
        danmakuSource,
        danmakuBinding,
        danmakuEnabled,
        danmakuReloads,
    ) {
        danmakuComments = emptyList()
        danmakuError = null
        danmakuMatch = null
        danmakuEpisodeId = null
        danmakuLoading = false
        val item = currentItem ?: return@LaunchedEffect
        if (!danmakuEnabled) return@LaunchedEffect
        val source = danmakuSource ?: return@LaunchedEffect
        val media = DanmakuMedia(
            id = item.id,
            title = item.title,
            season = item.seasonNumber,
            episode = item.episodeNumber,
            serverId = item.serverId,
        )

        danmakuLoading = true
        val loaded = when {
            // A hand-picked match outranks everything: it exists precisely because the
            // automatic one was wrong, and re-guessing would undo the correction.
            danmakuBinding != null -> {
                danmakuMatch = danmakuBinding.label
                danmakuEpisodeId = danmakuBinding.episodeId
                val pinned = danmakuSources.first { it.id == danmakuBinding.sourceId }
                danmakuRepository.loadEpisode(pinned, danmakuBinding.episodeId)
            }

            source.isTemplate -> danmakuRepository.load(source.url, media)

            else -> danmakuRepository.match(
                source = source,
                // A 弹幕 server files episodes under the show, not under the episode's own
                // title — "楼内暗藏玄机怪事频发" is in nobody's index.
                media = media.copy(
                    title = item.seriesName?.takeIf { it.isNotBlank() } ?: item.title,
                ),
            ).fold(
                onSuccess = { episode ->
                    if (episode == null) {
                        Result.failure(IllegalStateException("没有匹配到弹幕，可用搜索手动选择"))
                    } else {
                        danmakuMatch = episode.label
                        danmakuEpisodeId = episode.episodeId
                        danmakuRepository.loadEpisode(source, episode.episodeId)
                    }
                },
                onFailure = { Result.failure(it) },
            )
        }
        // Kept exactly as the server sent it. 合并重复 and the block list are applied on
        // the way to the screen, so toggling either is instant and 弹幕条数 keeps reporting
        // what the episode actually has rather than what survived the filter.
        loaded.fold(
            onSuccess = { danmakuComments = it },
            onFailure = { danmakuError = it.message ?: "弹幕加载失败" },
        )
        danmakuLoading = false
    }
    // Applied once per load rather than per frame — the overlay is already allocating lanes
    // sixty times a second — but not inside the loader, so toggling 合并重复 is instant
    // instead of re-downloading fourteen thousand comments to hide six of them.
    val danmakuVisible = remember(danmakuComments, danmakuMerge, danmakuBlocked) {
        DanmakuFilter.apply(danmakuComments, danmakuMerge, danmakuBlocked)
    }
    val danmakuActions = DanmakuPanelActions(
        onToggle = { danmakuPreferences.setEnabled(!danmakuEnabled) },
        onSelectArea = { index ->
            danmakuPreferences.setDisplayArea(DanmakuDisplayArea.entries[index])
        },
        onSelectFont = { index -> danmakuPreferences.setFontSize(DanmakuFontSize.entries[index]) },
        onSelectSpeed = { index -> danmakuPreferences.setSpeed(DanmakuSpeed.entries[index]) },
        onSelectOpacity = { index ->
            danmakuPreferences.setOpacity(DanmakuOpacity.entries[index])
        },
        onSelectSource = { id ->
            danmakuPreferences.selectSource(id)
            // Ids are per server. Keeping the old hits would offer results that the newly
            // selected source has never heard of.
            danmakuSearch = DanmakuSearchState(query = danmakuSearch.query)
        },
        onOpenSearch = {
            // Seeded with the show's name, which is what the index is keyed on and what
            // someone would have typed anyway.
            if (danmakuSearch.query.isBlank()) {
                val seed = currentItem?.let { item ->
                    item.seriesName?.takeIf { it.isNotBlank() } ?: item.title
                }
                danmakuSearch = danmakuSearch.copy(query = seed.orEmpty())
            }
        },
        onQueryChange = { danmakuSearch = danmakuSearch.copy(query = it) },
        onSubmitSearch = {
            val keyword = danmakuSearch.query.trim()
            if (danmakuSource != null && keyword.isNotEmpty()) {
                danmakuSearch = danmakuSearch.copy(
                    running = true,
                    error = null,
                    openResult = null,
                    episodes = emptyList(),
                )
                scope.launch {
                    danmakuPreferences.rememberSearch(keyword)
                    danmakuRepository.search(danmakuSource, keyword).fold(
                        onSuccess = { results ->
                            danmakuSearch = danmakuSearch.copy(
                                running = false,
                                results = results,
                                searched = true,
                            )
                        },
                        onFailure = { error ->
                            danmakuSearch = danmakuSearch.copy(
                                running = false,
                                results = emptyList(),
                                error = error.message ?: "搜索失败",
                            )
                        },
                    )
                }
            }
        },
        onOpenResult = { result ->
            danmakuSource?.let { source ->
                danmakuSearch = danmakuSearch.copy(
                    openResult = result,
                    episodes = emptyList(),
                    running = true,
                    error = null,
                )
                scope.launch {
                    danmakuRepository.episodes(source, result).fold(
                        onSuccess = { episodes ->
                            danmakuSearch = danmakuSearch.copy(
                                running = false,
                                episodes = episodes,
                            )
                        },
                        onFailure = { error ->
                            danmakuSearch = danmakuSearch.copy(
                                running = false,
                                error = error.message ?: "读取剧集失败",
                            )
                        },
                    )
                }
            }
        },
        onBackToResults = {
            danmakuSearch = danmakuSearch.copy(
                openResult = null,
                episodes = emptyList(),
                error = null,
            )
        },
        onPickEpisode = { episode ->
            val item = currentItem
            if (item != null && danmakuSource != null) {
                danmakuPreferences.bind(
                    itemId = danmakuBindingKey(
                        itemId = item.id,
                        title = item.title,
                        seriesName = item.seriesName,
                        seasonNumber = item.seasonNumber,
                        episodeNumber = item.episodeNumber,
                    ),
                    binding = DanmakuBinding(
                        sourceId = danmakuSource.id,
                        episodeId = episode.episodeId,
                        label = episode.label,
                    ),
                )
                // Picking an episode is a decision to watch with 弹幕; making that two
                // steps would be a chore with one sensible answer.
                if (!danmakuEnabled) danmakuPreferences.setEnabled(true)
            }
        },
        onToggleMerge = { danmakuPreferences.setMergeDuplicates(!danmakuMerge) },
        onRetry = { danmakuReloads++ },
        onSend = { text ->
            val source = danmakuSource
            val episodeId = danmakuEpisodeId
            if (source != null && episodeId != null) {
                danmakuSending = true
                danmakuSendError = null
                scope.launch {
                    danmakuRepository.send(
                        source = source,
                        episodeId = episodeId,
                        text = text,
                        // Read from the state this action object was built with, which is
                        // rebuilt on every position tick — so it is where the film is when
                        // 发送 is pressed, not where it was when the dialog opened.
                        positionMs = state.positionMs,
                    ).fold(
                        onSuccess = {
                            danmakuSending = false
                            // The line is on the server, not in the list on screen. One
                            // reload is cheaper than inventing a local comment that might
                            // not match what the server stored.
                            danmakuReloads++
                        },
                        onFailure = {
                            danmakuSending = false
                            danmakuSendError = it.message ?: "发送失败"
                        },
                    )
                }
            }
        },
        onClearMatch = {
            // Both keys: the current one, and the item id an older build may have used.
            danmakuKey?.let(danmakuPreferences::unbind)
            currentItem?.id?.let(danmakuPreferences::unbind)
        },
    )
    val latestEngine by rememberUpdatedState(engine)
    val latestPlaybackState by rememberUpdatedState(state)
    val mediaMatcher = remember(watchTogether) {
        mutableStateOf(WatchMediaMatcher { warning -> watchTogether.setSyncWarning(warning) })
    }.value

    // Guest side: the room's timeline is server-authoritative and near-silent between
    // events (see WatchTogetherClient), so following it needs a tick of our own rather
    // than only reacting to messages. Position drift is corrected in three tiers instead
    // of always hard-seeking: under NUDGE_THRESHOLD_MS is imperceptible and left alone;
    // under HARD_SEEK_THRESHOLD_MS is closed by nudging playback speed ±2% so the catch-up
    // is invisible; only a gap that large — a fresh join, a long stall — jumps outright.
    // The rate this computes is also enforced every tick regardless of drift, which is
    // what keeps 倍速 shared: a guest's own speed change from the settings menu gets
    // quietly overwritten back to the room's rate within one tick instead of needing a
    // separate lock on that control.
    LaunchedEffect(watchState.connected, watchState.reconnecting, watchState.isHost) {
        if (!watchState.connected || watchState.isHost) {
            mediaMatcher.reset()
            return@LaunchedEffect
        }
        var lastAppliedRate: Float? = null
        var lastNominalRate: Float? = null
        // What the last correction asked for, and when. A correction is a request, not an
        // event: a seek into an unbuffered part of a remote file, or an entry change, takes
        // longer than one tick, and the engine keeps reporting the old position until it
        // lands. Re-correcting in the meantime restarts the work — which is how a guest
        // joining a film already in progress ended up pinned on a black frame with its
        // timeline readout advancing, correcting forever and never rendering.
        var awaitedPositionMs: Long? = null
        var awaitedIndex: Int? = null
        var awaitingSince = TimeSource.Monotonic.markNow()
        fun awaitCorrection(positionMs: Long?, index: Int?) {
            awaitedPositionMs = positionMs
            awaitedIndex = index
            awaitingSince = TimeSource.Monotonic.markNow()
        }
        try {
            while (isActive) {
                val timeline = watchTogether.timeline.value
                if (timeline != null) {
                    lastNominalRate = timeline.rate
                    val targetIndex = mediaMatcher.resolve(items, timeline.mediaKey)
                    if (targetIndex != null) {
                        val position = latestEngine.currentPositionMs()
                        val landed = awaitedIndex.let { it == null || it == latestPlaybackState.currentIndex } &&
                            awaitedPositionMs.let {
                                it == null || kotlin.math.abs(position - it) < HARD_SEEK_THRESHOLD_MS
                            }
                        // Give up waiting eventually: a seek can also fail outright, and a
                        // guest stuck behind one correction forever is no better off.
                        val settling = !landed &&
                            awaitingSince.elapsedNow().inWholeMilliseconds < CORRECTION_SETTLE_TIMEOUT_MS
                        if (landed) awaitCorrection(null, null)
                        // Buffering means the engine is still working — on the last
                        // correction, or on the stream itself. Either way it has not yet
                        // shown where it really is, so there is nothing to correct against.
                        if (settling || latestPlaybackState.buffering) {
                            if (timeline.paused && latestPlaybackState.playing) latestEngine.pause()
                            delay(GUEST_RECONCILE_TICK_MS)
                            continue
                        }

                        if (targetIndex != latestPlaybackState.currentIndex) {
                            latestEngine.selectItem(targetIndex)
                            awaitCorrection(positionMs = null, index = targetIndex)
                            delay(GUEST_RECONCILE_TICK_MS)
                            continue
                        }
                        val expected = timeline.expectedPositionMs(watchTogether.estimatedServerNow())
                        val diff = expected - position
                        val desiredRate = when {
                            kotlin.math.abs(diff) >= HARD_SEEK_THRESHOLD_MS -> {
                                latestEngine.seekTo(expected)
                                awaitCorrection(positionMs = expected, index = null)
                                timeline.rate
                            }
                            kotlin.math.abs(diff) >= NUDGE_THRESHOLD_MS ->
                                timeline.rate * (1f + if (diff > 0) NUDGE_FRACTION else -NUDGE_FRACTION)
                            else -> timeline.rate
                        }
                        if (
                            lastAppliedRate == null ||
                            kotlin.math.abs(desiredRate - lastAppliedRate!!) > RATE_EPSILON
                        ) {
                            latestEngine.setSpeed(desiredRate)
                            lastAppliedRate = desiredRate
                        }
                        if (timeline.paused && latestPlaybackState.playing) latestEngine.pause()
                        if (!timeline.paused && !latestPlaybackState.playing) latestEngine.play()
                    }
                }
                delay(GUEST_RECONCILE_TICK_MS)
            }
        } finally {
            mediaMatcher.reset()
            lastNominalRate?.let(latestEngine::setSpeed)
        }
    }

    // Host side: publish a fresh anchor whenever we (re)gain control of the room, so a
    // reconnect refreshes the timeline to where playback actually is instead of leaving
    // guests following a stale pre-disconnect anchor. Every other publish happens at the
    // point of the action itself (play/pause/seek/select/speed below) — never on a timer.
    LaunchedEffect(watchState.connected, watchState.reconnecting, watchState.isHost) {
        if (watchState.connected && !watchState.reconnecting && watchState.isHost) {
            playbackGate.publishCurrent()
        }
    }
    val latestState by rememberUpdatedState(state)
    val reporter = remember(items, playbackSink) {
        playbackSink?.let { PlaybackProgressReporter(items, it) }
    }
    LaunchedEffect(state, reporter) {
        reporter?.update(state)
        onPlaybackState(state)
        playbackGate.onPlaybackIndexChanged(state.currentIndex)
        val item = activeItems.getOrNull(state.currentIndex)
        when {
            state.ended -> playbackRecovery.clear()
            item != null && state.positionMs >= 2_000L -> playbackRecovery.record(
                itemId = item.id,
                title = item.title,
                serverId = item.serverId,
                positionMs = state.positionMs,
                durationMs = state.durationMs,
                engine = state.diagnostics.engine,
            )
        }
    }
    DisposableEffect(reporter) {
        onDispose {
            reporter?.close(latestState)
            val finalState = latestState
            val item = items.getOrNull(finalState.currentIndex)
            if (item != null && !finalState.ended && finalState.positionMs >= 2_000L) {
                playbackRecovery.record(
                    itemId = item.id,
                    title = item.title,
                    serverId = item.serverId,
                    positionMs = finalState.positionMs,
                    durationMs = finalState.durationMs,
                    engine = finalState.diagnostics.engine,
                    force = true,
                )
            }
        }
    }

    /**
     * Plays the current entry from a different file. Position is read before the swap and
     * handed to the rebuilt engine, so switching a 4K remux for a 1080p encode keeps the
     * user's place instead of restarting the film.
     */
    fun selectVersion(versionId: String) {
        val item = activeItems.getOrNull(state.currentIndex) ?: return
        if (item.versionId == versionId) return
        if (item.versions.none { it.id == versionId }) return
        engine.pause()
        AppLog.info(
            category = "player",
            event = "version_switch_requested",
            message = "Playback media version switch requested",
            attributes = mapOf(
                "itemIndex" to state.currentIndex.toString(),
                "engine" to kind.name,
            ),
        )
        resume = state.currentIndex to engine.currentPositionMs()
        versionChoices = versionChoices + (item.id to versionId)
    }

    fun switchEngine(target: PlayerEngine) {
        if (target == kind) return
        // Read the position before the old engine is torn down.
        engine.pause()
        val positionMs = engine.currentPositionMs()
        AppLog.info(
            category = "player",
            event = "engine_switch_requested",
            message = "Playback engine switch requested",
            attributes = mapOf(
                "from" to kind.name,
                "to" to target.name,
                "itemIndex" to state.currentIndex.toString(),
                "positionMs" to positionMs.toString(),
            ),
        )
        resume = state.currentIndex to positionMs
        kind = target
    }

    // Last resort of the fallback chain: the entry has run out of streams on this engine,
    // so try a different decoder stack before giving up. Bounded by the set of engines
    // already tried for this entry, so a title nothing can play settles on an error
    // instead of cycling through the list forever.
    var enginesTried by remember(state.currentIndex) { mutableStateOf(setOf(kind)) }
    LaunchedEffect(state.fallbacksExhausted, state.currentIndex, kind) {
        if (!state.fallbacksExhausted) return@LaunchedEffect
        val next = PlayerEngine.selectable.firstOrNull { it !in enginesTried }
            ?: return@LaunchedEffect
        AppLog.info(
            category = "player",
            event = "engine_fallback",
            message = "Playback exhausted its streams; trying another engine",
            attributes = mapOf(
                "from" to kind.name,
                "to" to next.name,
                "itemIndex" to state.currentIndex.toString(),
            ),
        )
        enginesTried = enginesTried + next
        switchEngine(next)
    }
    val (volume, setVolume) = rememberSystemVolume()
    val (brightness, setBrightness) = rememberWindowBrightness()
    val castManager = remember { GlobalContext.get().get<CastManager>() }
    val castState by castManager.state.collectAsState()

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onGloballyPositioned { coordinates ->
                val bounds = coordinates.boundsInWindow()
                onVideoBounds(
                    Rect(
                        bounds.left.roundToInt(),
                        bounds.top.roundToInt(),
                        bounds.right.roundToInt(),
                        bounds.bottom.roundToInt(),
                    ),
                )
            },
    ) {
        when (engine) {
            is MdkVideoEngine -> MdkSurface(engine, Modifier.fillMaxSize())
            is MpvVideoEngine -> MpvSurface(engine, Modifier.fillMaxSize())
            is ExoVideoEngine -> ExoSurface(engine, filled, Modifier.fillMaxSize())
        }

        if (!inPictureInPicture && danmakuEnabled && danmakuVisible.isNotEmpty()) {
            DanmakuOverlay(
                comments = danmakuVisible,
                positionMs = state.positionMs,
                playing = state.playing && !state.buffering,
                playbackRate = state.speed,
                displayArea = danmakuArea,
                fontSize = danmakuFont,
                speed = danmakuSpeed,
                opacity = danmakuOpacity,
            )
        }

        if (!inPictureInPicture) {
            PlayerControls(
                state = state,
                episodes = activeItems.toEpisodeCards(),
                filled = filled,
                onBack = onBack,
                onPlayPause = { playbackGate.togglePlayPause() },
                onRetry = { playbackGate.retry() },
                onSeek = playbackGate::seekTo,
                onSelectItem = playbackGate::selectItem,
                onSelectAudio = engine::selectAudioTrack,
                onSelectSubtitle = engine::selectSubtitleTrack,
                onSpeed = { newSpeed -> playbackGate.setSpeed(newSpeed) },
                onToggleFill = {
                    filled = !filled
                    (engine as? MpvVideoEngine)?.setFill(filled)
                    (engine as? MdkVideoEngine)?.setFill(filled)
                },
                volume = volume,
                onVolume = { setVolume(it) },
                volumeKeyPresses = volumeKeyPresses.collectAsState().value,
                brightness = brightness,
                onBrightness = { setBrightness(it) },
                engineOptions = PlayerEngine.selectable.map { it.label to (it == kind) },
                onSelectEngine = { index -> switchEngine(PlayerEngine.selectable[index]) },
                // Manual escape hatch when the picture is black but audio plays. Offered on
                // every engine now — it used to be ExoPlayer-only, which left the native
                // engines with no way out of a file the device can't decode.
                transcodeLabel = "转码播放",
                transcodeActive = state.transcoding,
                onTranscode = { engine.switchToTranscode() },
                castDevices = castState.devices.map { it.id to it.name },
                castingDeviceId = castState.activeDeviceId,
                castDiscovering = castState.discovering,
                castError = castState.error,
                onDiscoverCast = { scope.launch { castManager.discover() } },
                onCastTo = { deviceId ->
                    val item = activeItems.getOrNull(state.currentIndex) ?: return@PlayerControls
                    scope.launch {
                        castManager.play(deviceId, item.url, item.title)
                        if (castManager.state.value.activeDeviceId == deviceId) {
                            playbackGate.pause()
                        }
                    }
                },
                onStopCast = {
                    scope.launch {
                        castManager.stop()
                        playbackGate.play()
                    }
                },
                danmaku = DanmakuPanelState(
                    sources = danmakuSources,
                    activeSourceId = danmakuActiveSourceId,
                    enabled = danmakuEnabled,
                    count = danmakuComments.size,
                    loading = danmakuLoading,
                    error = danmakuError,
                    matchLabel = danmakuMatch,
                    matchPinned = danmakuBinding != null,
                    mergeDuplicates = danmakuMerge,
                    // Only a real server takes writes, and only once something is matched
                    // — there is no episode to post against otherwise.
                    canSend = danmakuSource?.supportsSearch == true && danmakuEpisodeId != null,
                    sending = danmakuSending,
                    sendError = danmakuSendError,
                    areaOptions = DanmakuDisplayArea.entries.map {
                        it.label to (it == danmakuArea)
                    },
                    fontOptions = DanmakuFontSize.entries.map {
                        it.label to (it == danmakuFont)
                    },
                    speedOptions = DanmakuSpeed.entries.map {
                        it.label to (it == danmakuSpeed)
                    },
                    opacityOptions = DanmakuOpacity.entries.map {
                        it.label to (it == danmakuOpacity)
                    },
                    search = danmakuSearch.copy(recent = danmakuRecent),
                ),
                danmakuActions = danmakuActions,
                // Only worth naming when there is more than one server to be on. On a
                // single-server install it is a constant, and a constant on a line meant
                // for live facts is noise.
                sourceLabel = currentItem?.serverId
                    ?.takeIf { serverNames.size > 1 }
                    ?.let(serverNames::get),
                containerLabel = currentItem?.activeVersion?.container,
                dolbyVision = currentItem?.activeVersion?.dolbyVision == true,
                dolbyAtmos = currentItem?.activeVersion?.dolbyAtmos == true,
                versions = currentItem?.versions.orEmpty().map { version ->
                    version.id to listOfNotNull(
                        version.label,
                        version.detail.takeIf { it.isNotBlank() },
                    ).joinToString(" · ")
                },
                selectedVersionId = currentItem?.versionId,
                onSelectVersion = ::selectVersion,
                skip = SkipSegmentState(
                    // 关闭 keeps the stored boundaries and stops offering them. Gating
                    // here rather than on the segment itself leaves 片头片尾 in the
                    // settings panel still showing what is set — turning the offer off is
                    // not forgetting it.
                    segmentLabel = activeSegment?.type?.skipLabel
                        ?.takeIf { skipMode != SkipMode.Off },
                    countdownSeconds = skipCountdownSeconds,
                    seriesName = currentItem?.seriesId?.let {
                        currentItem.seriesName?.ifBlank { null } ?: "本剧"
                    },
                    introStartSeconds = skipTimes?.introStartSeconds ?: 0L,
                    introEndSeconds = skipTimes?.introEndSeconds ?: 0L,
                    creditsLeadSeconds = skipTimes?.creditsLeadSeconds ?: 0L,
                    mode = skipMode,
                ),
                skipActions = SkipSegmentActions(
                    onSkip = skipSegment,
                    // Cancelling drops back to the manual pill rather than clearing the
                    // offer outright: "not automatically" is not "not at all".
                    onCancelAuto = { settledSkip = skipOccurrence },
                    onSetTimes = { introStart, introEnd, creditsLead ->
                        val seriesId = currentItem?.seriesId
                        if (seriesId != null) {
                            skipSegmentPreferences.set(
                                seriesId = seriesId,
                                times = SkipTimes(
                                    introStartSeconds = introStart,
                                    introEndSeconds = introEnd,
                                    creditsLeadSeconds = creditsLead,
                                    seriesName = currentItem.seriesName.orEmpty(),
                                ),
                            )
                        }
                    },
                    onSelectMode = skipSegmentPreferences::setSkipMode,
                ),
                watch = WatchRoomState(
                    endpoint = watchEndpoint,
                    connecting = watchState.connecting,
                    connected = watchState.connected,
                    reconnecting = watchState.reconnecting,
                    roomCode = watchState.roomCode,
                    isHost = watchState.isHost,
                    participantCount = watchState.participantCount,
                    error = watchState.error ?: watchState.syncWarning,
                    controlRequested = watchState.controlRequested,
                    controlRequesterName = watchState.controlRequest?.name,
                ),
                watchActions = WatchRoomActions(
                    onCreate = { endpoint ->
                        currentItem?.let { item ->
                            watchTogether.createRoom(endpoint, item.watchKey)
                        }
                    },
                    onJoin = { endpoint, roomCode ->
                        currentItem?.let { item ->
                            watchTogether.joinRoom(endpoint, roomCode, item.watchKey)
                        }
                    },
                    onLeave = watchTogether::leave,
                    onRequestControl = watchTogether::requestControl,
                    onGrantControl = {
                        watchState.controlRequest?.let { watchTogether.grantControl(it.clientId) }
                    },
                    onDenyControl = {
                        watchState.controlRequest?.let { watchTogether.denyControl(it.clientId) }
                    },
                ),
            )
        }
    }
}

/**
 * The engine track that answers to a language the detail page named.
 *
 * Deliberately forgiving, in that order: an exact language-code match, then a code sharing
 * its first two letters, then a label that mentions it. Emby reports `chi`, an engine may
 * report `zho` or nothing at all and put 国语 only in the title, and a picker that silently
 * does nothing because two three-letter codes disagree is worse than one that reads the
 * label. No match returns null and the file's own default stands — which is where it began.
 */
private fun List<EngineTrack>.matching(language: String): String? {
    val wanted = language.trim().lowercase()
    if (wanted.isEmpty()) return null
    return firstOrNull { it.language?.lowercase() == wanted }?.id
        ?: firstOrNull { it.language?.lowercase()?.startsWith(wanted.take(2)) == true }?.id
        ?: firstOrNull { it.label.contains(language, ignoreCase = true) }?.id
}

@Composable
private fun rememberWindowBrightness(): Pair<Float, (Float) -> Unit> {
    val activity = LocalContext.current as? Activity
    var level by remember(activity) {
        val current = activity?.window?.attributes?.screenBrightness ?: -1f
        mutableFloatStateOf(if (current in 0f..1f) current else 0.5f)
    }
    return level to { target: Float ->
        val clamped = target.coerceIn(0.02f, 1f)
        level = clamped
        activity?.window?.let { window ->
            window.attributes = window.attributes.apply { screenBrightness = clamped }
        }
    }
}

/** Reads and writes `STREAM_MUSIC`, so the player's level chip is the real volume. */
@Composable
private fun rememberSystemVolume(): Pair<Float, (Float) -> Unit> {
    val context = LocalContext.current
    val audio = remember(context) { context.getSystemService(AudioManager::class.java) }
    val max = remember(audio) { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    val min = remember(audio) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            audio.getStreamMinVolume(AudioManager.STREAM_MUSIC)
        } else {
            0
        }
    }
    var level by remember(audio, min, max) {
        mutableFloatStateOf(
            streamVolumeFraction(
                current = audio.getStreamVolume(AudioManager.STREAM_MUSIC),
                min = min,
                max = max,
            ),
        )
    }
    DisposableEffect(context, audio, min, max) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                level = streamVolumeFraction(
                    current = audio.getStreamVolume(AudioManager.STREAM_MUSIC),
                    min = min,
                    max = max,
                )
            }
        }
        context.contentResolver.registerContentObserver(
            Settings.System.CONTENT_URI,
            true,
            observer,
        )
        onDispose {
            runCatching { context.contentResolver.unregisterContentObserver(observer) }
        }
    }
    return level to { target: Float ->
        val requested = streamVolumeForFraction(target, min, max)
        runCatching {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, requested, 0)
        }.onFailure {
            AppLog.warning(
                category = "player.audio",
                event = "volume_change_failed",
                message = "System media volume could not be changed",
                throwable = it,
                attributes = mapOf("requestedLevel" to requested.toString()),
            )
        }
        // Read the value back. OEM safe-volume policy can clamp the request, and
        // showing the requested value would make the control look stuck or dishonest.
        level = streamVolumeFraction(
            current = audio.getStreamVolume(AudioManager.STREAM_MUSIC),
            min = min,
            max = max,
        )
    }
}

internal fun streamVolumeFraction(current: Int, min: Int, max: Int): Float {
    if (max <= min) return 0f
    return (current.coerceIn(min, max) - min).toFloat() / (max - min)
}

internal fun streamVolumeForFraction(fraction: Float, min: Int, max: Int): Int {
    if (max <= min) return min
    return (min + fraction.coerceIn(0f, 1f) * (max - min))
        .roundToInt()
        .coerceIn(min, max)
}

@OptIn(UnstableApi::class)
@Composable
private fun ExoSurface(engine: ExoVideoEngine, filled: Boolean, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                keepScreenOn = true
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
        },
        update = { view ->
            // Reassigned on update too: a fresh engine reuses this same view.
            if (view.player !== engine.player) view.player = engine.player
            view.resizeMode = if (filled) {
                AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            } else {
                AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
        },
        modifier = modifier,
    )
}
