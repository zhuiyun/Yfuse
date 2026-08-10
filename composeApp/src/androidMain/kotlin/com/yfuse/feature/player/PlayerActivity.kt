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
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
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
import com.yfuse.core.data.PlaybackPreferences
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
import com.yfuse.core.model.languageDisplayName
import com.yfuse.core.model.PlaybackSegment
import com.yfuse.core.model.PlaybackSegmentType
import com.yfuse.core.model.PlayerEngine
import com.yfuse.core.network.EmbyStream
import com.yfuse.core.network.EmbyImages
import com.yfuse.core.sync.episodeWatchKey
import com.yfuse.core.sync.watchMatchKeys
import com.yfuse.core.sync.WatchTogetherClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.context.GlobalContext
import kotlin.math.roundToInt
import kotlin.time.TimeSource

/**
 * Fullscreen playback lives in its own activity so landscape is declared in the
 * manifest rather than forced at runtime (which misbehaves on some devices).
 */
class PlayerActivity : ComponentActivity() {

    companion object {
        internal const val NOTIFICATION_CHANNEL = "yfuse_playback"
        internal const val NOTIFICATION_ID = 2407
        private const val NOTIFICATION_PERMISSION_REQUEST = 2408
        private const val ACTION_PREVIOUS = "com.yfuse.player.PREVIOUS"
        private const val ACTION_PLAY_PAUSE = "com.yfuse.player.PLAY_PAUSE"
        private const val ACTION_NEXT = "com.yfuse.player.NEXT"
        private const val ACTION_OPEN = "com.yfuse.player.OPEN"
        private const val EPISODE_REFRESH_INTERVAL_MS = 120_000L

        fun intent(
            context: Context,
            items: List<PlayerMediaItem>,
            startIndex: Int,
            startPositionMs: Long,
            engine: PlayerEngine,
            decoder: DecoderMode,
            autoNext: Boolean,
            quality: PlaybackQuality,
        ): Intent {
            val request = PlayerLaunchRequest.create(
                items = items,
                startIndex = startIndex,
                startPositionMs = startPositionMs,
                engine = engine,
                decoder = decoder,
                autoNext = autoNext,
                quality = quality,
            )
            val payload = PlayerLaunchIntentPayload.create(
                request = request,
                launchId = PlayerLaunchRegistry.register(request),
            )
            // Reuse a live player even if another activity is above it. onNewIntent consumes the
            // fresh one-shot token and recreates this single instance with the replacement queue.
            return Intent(context, PlayerActivity::class.java)
                .apply(payload::writeTo)
                .addFlags(
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                )
        }

        /** Brings a live player forward without copying its queue or reusing its consumed token. */
        internal fun openIntent(context: Context): Intent =
            Intent(context, PlayerActivity::class.java)
                .setAction(ACTION_OPEN)
                .addFlags(
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                )

        internal fun discardLaunch(intent: Intent) {
            PlayerLaunchRegistry.discard(PlayerLaunchIntentPayload.readFrom(intent)?.launchId)
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
    private var activityStarted = false
    private var playbackKeepAliveRequested = false
    private var playbackKeepAliveStartDeferred = false
    private val playbackItems = MutableStateFlow<List<PlayerMediaItem>>(emptyList())
    private val queueResume = MutableStateFlow(0 to 0L)
    private val queueRevision = MutableStateFlow(0L)
    private lateinit var embyRepository: EmbyRepository
    private lateinit var serverRegistry: ServerRegistry
    private var episodeRefreshJob: Job? = null
    private var episodePollingJob: Job? = null
    private val launchViewModel by viewModels<PlayerLaunchViewModel>()
    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasAudioFocus = true
                if (resumeAfterTransientFocusLoss) {
                    resumeAfterTransientFocusLoss = false
                    // Audio focus is local to this device, not a room timeline action.
                    // Going through the guest gate would refuse the resume and leave this
                    // participant with picture but no sound after a transient interruption.
                    activeEngine?.play()
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
                activeEngine?.pause()
                AppLog.info(
                    category = "player.audio",
                    event = "focus_lost_transient",
                    message = "Playback paused for a transient audio focus loss",
                )
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                resumeAfterTransientFocusLoss = false
                hasAudioFocus = false
                activeEngine?.pause()
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

        val launchPayload = PlayerLaunchIntentPayload.readFrom(intent)
        val launchResolution = resolvePlayerLaunch(
            retained = launchViewModel.request,
            payload = launchPayload,
        )
        if (launchResolution is PlayerLaunchResolution.Expired) {
            AppLog.warning(
                category = "feature.player",
                event = "launch_expired",
                message = "Player launch data was missing or expired",
                attributes = buildMap {
                    put("hasLaunchId", (launchPayload != null).toString())
                    launchResolution.fallback?.let { fallback ->
                        put("itemId", fallback.itemId)
                        fallback.serverId?.let { put("serverId", it) }
                    }
                },
            )
            Toast.makeText(this, "播放会话已过期，请重新打开", Toast.LENGTH_SHORT).show()
            clearStalePlaybackArtifacts()
            finish()
            return
        }
        val launchRequest = (launchResolution as PlayerLaunchResolution.Ready).request
        launchViewModel.request = launchRequest
        val items = launchRequest.items
        val initialEngine = launchRequest.engine
        val decoderMode = launchRequest.decoder
        val autoNext = launchRequest.autoNext
        val quality = launchRequest.quality
        val retainedResume = launchViewModel.resume
        val initialStartIndex = retainedResume?.first ?: launchRequest.startIndex
        val initialStartPositionMs = retainedResume?.second ?: launchRequest.startPositionMs
        playbackItems.value = items
        pictureInPicture.value = isInPictureInPictureMode
        pipWasVisible = isInPictureInPictureMode
        sessionTitles = items.map { it.title }
        createMediaSession()
        createPlaybackNotificationChannel()
        registerMediaActionReceiver()
        requestNotificationPermissionIfNeeded()

        val koin = GlobalContext.get()
        embyRepository = koin.get()
        serverRegistry = koin.get()
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
        val videoCacheBytes = koin.get<PlaybackPreferences>().videoCacheSize.value.bytes
        val customUserAgent = koin.get<UserAgentPreferences>().userAgent.value
        val watchTogether = koin.get<WatchTogetherClient>()
        val watchTogetherPreferences = koin.get<WatchTogetherPreferences>()
        val playbackController = WatchGatedPlayback(
            watchTogether = watchTogether,
            items = { playbackItems.value },
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
                initialStartIndex,
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
            val liveItems by playbackItems.collectAsState()
            val refreshedResume by queueResume.collectAsState()
            val refreshedRevision by queueRevision.collectAsState()
            // Always the dark palette: the controls float over the picture.
            YfuseTheme(dark = true, accent = accent) {
                PlayerRoot(
                    items = liveItems,
                    startIndex = initialStartIndex,
                    startPositionMs = initialStartPositionMs,
                    refreshedResume = refreshedResume,
                    queueRevision = refreshedRevision,
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
                    videoCacheBytes = videoCacheBytes,
                    watchTogether = watchTogether,
                    watchTogetherPreferences = watchTogetherPreferences,
                    playbackGate = playbackController,
                    onEngineAttached = { engine -> activeEngine = engine },
                    onEngineDetached = { engine ->
                        if (activeEngine === engine) activeEngine = null
                    },
                    onPlaybackState = { state, item ->
                        activeState = state
                        if (
                            launchViewModel.request === launchRequest &&
                            state.currentIndex in playbackItems.value.indices
                        ) {
                            launchViewModel.resume = state.currentIndex to state.positionMs.coerceAtLeast(0L)
                        }
                        ActivePlayback.update(
                            item?.title.orEmpty(),
                            state,
                        )
                        if (item != null && state.currentIndex in sessionTitles.indices) {
                            sessionTitles = playbackItems.value.map { it.title }
                        }
                        updateMediaSession(state)
                        updatePictureInPictureParams()
                        if (state.playing) {
                            startPlaybackKeepAliveService()
                        } else {
                            stopPlaybackKeepAliveService()
                        }
                    },
                    onVideoBounds = { bounds ->
                        videoBounds = bounds
                        updatePictureInPictureParams()
                    },
                    onBack = ::closePlayerAndReturn,
                    onEnterPictureInPicture = ::enterPlayerPictureInPicture,
                    onRefreshEpisodes = ::refreshEpisodes,
                    onRemotePlayRequested = ::ensureAudioFocus,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Notification taps only bring this live instance forward. They deliberately carry no
        // launch token and must never restart playback or consume registry state.
        if (intent.action == ACTION_OPEN) return

        val payload = PlayerLaunchIntentPayload.readFrom(intent) ?: return
        when (val replacement = resolveFreshPlayerLaunch(payload)) {
            is PlayerLaunchResolution.Ready -> {
                setIntent(intent)
                launchViewModel.request = replacement.request
                launchViewModel.resume = null
                // Suppress leave-to-PiP while Activity.recreate tears down the old composition.
                stopRequested = true
                AppLog.info(
                    category = "feature.player",
                    event = "launch_replaced",
                    message = "Active player is being replaced with a new launch request",
                    attributes = mapOf("itemCount" to replacement.request.items.size.toString()),
                )
                recreate()
            }
            is PlayerLaunchResolution.Expired -> {
                AppLog.warning(
                    category = "feature.player",
                    event = "replacement_launch_expired",
                    message = "Replacement player launch data was missing or expired",
                )
                Toast.makeText(
                    this,
                    "新的播放会话已过期，继续当前播放",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        activityStarted = true
        if (activeState.playing) startPlaybackKeepAliveService()
        refreshEpisodes()
        episodePollingJob?.cancel()
        episodePollingJob = lifecycleScope.launch {
            while (isActive) {
                delay(EPISODE_REFRESH_INTERVAL_MS)
                refreshEpisodes()
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S &&
            activeState.playing &&
            !isFinishing &&
            !stopRequested
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
        activityStarted = false
        episodePollingJob?.cancel()
        episodePollingJob = null
        super.onStop()
        // Android can keep a closed PiP activity stopped but alive. Explicitly
        // tear down its engine so audio cannot continue invisibly.
        if (pipWasVisible && !isChangingConfigurations) {
            stopPlaybackAndFinish()
        }
    }

    override fun onDestroy() {
        episodeRefreshJob?.cancel()
        episodePollingJob?.cancel()
        ActivePlayback.clear()
        stopPlaybackKeepAliveService()
        abandonAudioFocus()
        if (::notificationManager.isInitialized) {
            notificationManager.cancel(NOTIFICATION_ID)
        }
        if (mediaReceiverRegistered) {
            runCatching { unregisterReceiver(mediaActionReceiver) }
            mediaReceiverRegistered = false
        }
        if (::mediaSession.isInitialized) {
            mediaSession.isActive = false
            mediaSession.release()
        }
        super.onDestroy()
        playbackGate = null
    }

    /** Removes notifications/services left behind when process death made a launch token stale. */
    private fun clearStalePlaybackArtifacts() {
        runCatching {
            stopService(Intent(this, PlaybackKeepAliveService::class.java))
        }
        runCatching {
            getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        }
    }

    private fun closePlayerAndReturn() {
        if (stopRequested) return
        // Mark the activity as closing before finishing it. Otherwise onUserLeaveHint can
        // race this path and turn a deliberate close into PiP. MainActivity stays directly
        // underneath this activity in the same task, so finish() restores it without a relaunch.
        stopRequested = true
        activeEngine?.release()
        activeEngine = null
        abandonAudioFocus()
        ActivePlayback.clear()
        stopPlaybackKeepAliveService()
        finish()
    }

    private fun enterPlayerPictureInPicture() {
        if (isFinishing || stopRequested || isInPictureInPictureMode) return
        enterPictureInPictureMode(
            PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .apply { videoBounds?.let(::setSourceRectHint) }
                .build(),
        )
    }

    private fun stopPlaybackAndFinish() {
        if (stopRequested) return
        stopRequested = true
        activeEngine?.release()
        activeEngine = null
        abandonAudioFocus()
        ActivePlayback.clear()
        stopPlaybackKeepAliveService()
        finish()
    }

    /**
     * Refreshes a series queue from the server instead of freezing it at detail-page time.
     *
     * Existing entries win when they carry richer detail/version data. Display metadata such as
     * artwork and progress is applied without touching the engine; PlayerRoot only restarts at the
     * current item and position when playable sources are added, removed, reordered or replaced.
     */
    private fun refreshEpisodes() {
        if (episodeRefreshJob?.isActive == true) return
        val snapshot = playbackItems.value
        val seed = snapshot.firstOrNull { it.seriesId != null && it.serverId != null } ?: return
        val seriesId = seed.seriesId ?: return
        val server = seed.serverId?.let(serverRegistry::serverById) ?: return

        episodeRefreshJob = lifecycleScope.launch {
            val seriesProviderIds = embyRepository.itemDetail(server, seriesId)
                .getOrNull()
                ?.providerIds
                .orEmpty()
            val episodes = embyRepository.episodes(
                server,
                seriesId,
                null,
                includeMediaSources = true,
            )
                .onFailure { error ->
                    AppLog.warning(
                        category = "player.queue",
                        event = "episode_refresh_failed",
                        message = "Player episode queue refresh failed",
                        throwable = error,
                        attributes = mapOf("serverId" to server.id),
                    )
                }
                .getOrNull()
                .orEmpty()
            if (episodes.isEmpty()) return@launch

            val existing = playbackItems.value.associateBy(PlayerMediaItem::id)
            val refreshed = episodes.map { episode ->
                val title = listOfNotNull(
                    episode.indexNumber?.let { "第 $it 集" },
                    episode.name.takeIf { it.isNotBlank() },
                ).joinToString("  ")
                val stillUrl = EmbyImages.primary(
                    server.baseUrl,
                    episode.id,
                    episode.primaryTag,
                    maxHeight = 240,
                    accessToken = server.accessToken,
                )
                val progress = when {
                    episode.played -> 1f
                    else -> episode.playedPercentage?.let { (it / 100.0).toFloat() }
                }
                existing[episode.id]?.copy(
                    title = title,
                    playbackSegments = episode.playbackSegments,
                    seasonNumber = episode.seasonNumber,
                    episodeNumber = episode.indexNumber,
                    stillUrl = stillUrl,
                    progress = progress,
                    caption = episode.indexNumber?.let { "第 $it 集" },
                ) ?: run {
                    val versions = episode.versions.toPlayerMediaVersions(
                        baseUrl = server.baseUrl,
                        itemId = episode.id,
                        token = server.accessToken,
                    )
                    val selected = versions.firstOrNull()
                    val unqualified = if (selected == null) {
                        EmbyStream.streamUrls(
                            baseUrl = server.baseUrl,
                            itemId = episode.id,
                            token = server.accessToken,
                        )
                    } else {
                        null
                    }
                    PlayerMediaItem(
                        id = episode.id,
                        url = selected?.url ?: requireNotNull(unqualified).direct,
                        transcodeUrl = selected?.transcodeUrl ?: requireNotNull(unqualified).transcode,
                        fallbackTranscodeUrl = selected?.fallbackTranscodeUrl
                            ?: requireNotNull(unqualified).progressiveTranscode,
                        playSessionId = selected?.playSessionId
                            ?: requireNotNull(unqualified).playSessionId,
                        versions = versions,
                        versionId = selected?.id,
                        title = title,
                        serverId = server.id,
                        playbackSegments = episode.playbackSegments,
                        seasonNumber = episode.seasonNumber,
                        episodeNumber = episode.indexNumber,
                        seriesId = seriesId,
                        seriesName = seed.seriesName,
                        watchKey = episodeWatchKey(
                            ownProviderIds = episode.providerIds,
                            seriesProviderIds = seriesProviderIds,
                            seasonNumber = episode.seasonNumber,
                            episodeNumber = episode.indexNumber,
                            fallbackId = episode.id,
                        ),
                        matchKeys = watchMatchKeys(
                            ownProviderIds = episode.providerIds,
                            seriesProviderIds = seriesProviderIds,
                            seasonNumber = episode.seasonNumber,
                            episodeNumber = episode.indexNumber,
                            fallbackId = episode.id,
                        ),
                        stillUrl = stillUrl,
                        progress = progress,
                        caption = episode.indexNumber?.let { "第 $it 集" },
                    )
                }
            }
            val current = playbackItems.value
            if (refreshed == current) return@launch

            // A show that published an episode while this one plays is the common case, and
            // the engine can take it as an extension of its playlist. Only a change it
            // cannot absorb that way is worth interrupting the viewer for.
            val appended = current.appendedBy(refreshed)
            val absorbed = appended != null && activeEngine?.appendItems(appended) == true
            val playbackSourcesChanged =
                !absorbed && !current.hasSamePlaybackSourcesAs(refreshed)
            if (playbackSourcesChanged) {
                val playingId = current.getOrNull(activeState.currentIndex)?.id
                val refreshedIndex =
                    refreshed
                        .indexOfFirst { it.id == playingId }
                        .takeIf { it >= 0 }
                        ?: activeState.currentIndex.coerceIn(0, refreshed.lastIndex)
                queueResume.value = refreshedIndex to
                    (activeEngine?.currentPositionMs() ?: activeState.positionMs)
            }
            playbackItems.value = refreshed
            sessionTitles = refreshed.map { it.title }
            if (playbackSourcesChanged) queueRevision.value++
            AppLog.info(
                category = "player.queue",
                event = "episodes_refreshed",
                message = "Player episode queue refreshed",
                attributes =
                    mapOf(
                        "itemCount" to refreshed.size.toString(),
                        "appendedCount" to (appended?.size ?: 0).toString(),
                        "queueExtended" to absorbed.toString(),
                        "engineRestarted" to playbackSourcesChanged.toString(),
                    ),
            )
        }
    }

    private fun createMediaSession() {
        mediaSession = MediaSession(this, "YfusePlayer").apply {
            setCallback(
                object : MediaSession.Callback() {
                    override fun onPlay() {
                        if (ensureAudioFocus()) {
                            startPlaybackKeepAliveService(fromUserAction = true)
                            playbackGate?.play()
                        }
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
            activeEngine?.pause()
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
            startPlaybackKeepAliveService(fromUserAction = true)
            playbackGate?.play()
        }
    }

    /**
     * Requests the playback foreground service once per playing interval.
     *
     * Engine state is emitted continuously, including while this activity is in PiP or stopped.
     * Calling `startForegroundService` for every emission eventually makes Android treat one as a
     * background start and throw `ForegroundServiceStartNotAllowedException`. A rejected request
     * is deferred until the activity becomes visible again; notification/media-session actions
     * get one immediate retry because they are explicit user actions.
     */
    private fun startPlaybackKeepAliveService(fromUserAction: Boolean = false) {
        if (playbackKeepAliveRequested) return
        if (playbackKeepAliveStartDeferred && !activityStarted && !fromUserAction) return

        try {
            ContextCompat.startForegroundService(
                this,
                Intent(this, PlaybackKeepAliveService::class.java),
            )
            playbackKeepAliveRequested = true
            playbackKeepAliveStartDeferred = false
        } catch (exception: IllegalStateException) {
            // Android 8+ reports prohibited background service starts as IllegalStateException;
            // Android 12+'s ForegroundServiceStartNotAllowedException is a subclass of it.
            playbackKeepAliveStartDeferred = true
            AppLog.warning(
                category = "player.service",
                event = "foreground_start_deferred",
                message =
                    "Playback foreground service start was deferred until the player is visible",
                throwable = exception,
                attributes =
                    mapOf(
                        "activityStarted" to activityStarted.toString(),
                        "fromUserAction" to fromUserAction.toString(),
                    ),
            )
        }
    }

    private fun stopPlaybackKeepAliveService() {
        if (!playbackKeepAliveRequested && !playbackKeepAliveStartDeferred) return
        stopService(Intent(this, PlaybackKeepAliveService::class.java))
        playbackKeepAliveRequested = false
        playbackKeepAliveStartDeferred = false
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
            openIntent(this),
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

/** A guest that remains buffering this long gets one active recovery attempt. */
private const val GUEST_BUFFER_RECOVERY_MS = 15_000L

/** Loading completion realigns more aggressively than ordinary in-play drift. */
private const val POST_BUFFER_SEEK_THRESHOLD_MS = 300L

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
    refreshedResume: Pair<Int, Long>,
    queueRevision: Long,
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
    videoCacheBytes: Long,
    watchTogether: WatchTogetherClient,
    watchTogetherPreferences: WatchTogetherPreferences,
    playbackGate: WatchGatedPlayback,
    onEngineAttached: (VideoEngine) -> Unit,
    onEngineDetached: (VideoEngine) -> Unit,
    onPlaybackState: (PlaybackState, PlayerMediaItem?) -> Unit,
    onVideoBounds: (Rect) -> Unit,
    onBack: () -> Unit,
    onEnterPictureInPicture: () -> Unit,
    onRefreshEpisodes: () -> Unit,
    onRemotePlayRequested: () -> Boolean,
) {
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
    var engineGeneration by remember { mutableIntStateOf(0) }
    var scaleMode by remember { mutableStateOf(VideoScaleMode.Fit) }
    var subtitleControls by remember { mutableStateOf(SubtitleControlState()) }
    var pendingSubtitleLanguage by remember { mutableStateOf<String?>(null) }

    // Entry id -> chosen file, for titles the server holds more than one copy of. Switching
    // rebuilds the queue and restarts the engine at the same position, which is the same
    // handover an engine switch already performs — no engine needs to know about versions.
    var versionChoices by remember {
        mutableStateOf(emptyMap<String, PlayerMediaVersion>())
    }
    val activeItems = remember(items, versionChoices) {
        if (versionChoices.isEmpty()) {
            items
        } else {
            items.map { item ->
                versionChoices[item.id]?.let(item::withVersion) ?: item
            }
        }
    }

    // A refreshed queue is applied as one deliberate engine handover. Keeping activeItems out
    // of the remember key prevents a transient recomposition from rebuilding at a stale point.
    LaunchedEffect(queueRevision) {
        if (queueRevision <= 0L) return@LaunchedEffect
        resume = refreshedResume
        engineGeneration++
    }

    val engine: VideoEngine = remember(kind, engineGeneration) {
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
                stopEncoding = { sessionId ->
                    playbackSink?.stopEncoding(sessionId) ?: true
                },
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
                scope = scope,
                stopEncoding = { sessionId ->
                    playbackSink?.stopEncoding(sessionId) ?: true
                },
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
                videoCacheBytes = videoCacheBytes,
                stopEncoding = { sessionId ->
                    playbackSink?.stopEncoding(sessionId) ?: true
                },
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
    val watchChatPreview by watchTogetherPreferences.chatPreviewEnabled.collectAsState()
    val watchChatDanmaku by watchTogetherPreferences.chatDanmakuEnabled.collectAsState()
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
    val remoteSubtitleRepository = remember { GlobalContext.get().get<EmbyRepository>() }
    val remoteSubtitleRegistry = remember { GlobalContext.get().get<ServerRegistry>() }
    var remoteSubtitles by remember(currentItem?.serverId, currentItem?.id) {
        mutableStateOf(RemoteSubtitlePanelState())
    }
    val remoteSubtitleActions = RemoteSubtitleActions(
        onSearch = {
            val item = currentItem
            val server = item?.serverId?.let(remoteSubtitleRegistry::serverById)
            if (item != null && server != null && !remoteSubtitles.loading) {
                remoteSubtitles = remoteSubtitles.copy(loading = true, message = null)
                scope.launch {
                    remoteSubtitleRepository.searchRemoteSubtitles(server, item.id)
                        .onSuccess { results ->
                            remoteSubtitles = remoteSubtitles.copy(
                                loading = false,
                                results = results.map { result ->
                                    RemoteSubtitleOption(
                                        id = result.Id,
                                        label = result.Name ?: result.Language ?: "中文字幕",
                                        detail = listOfNotNull(result.ProviderName, result.Format?.uppercase())
                                            .joinToString(" · "),
                                    )
                                },
                                message = "未找到字幕".takeIf { results.isEmpty() },
                            )
                        }
                        .onFailure { error ->
                            remoteSubtitles = remoteSubtitles.copy(
                                loading = false,
                                message = error.message ?: "字幕搜索失败",
                            )
                        }
                }
            }
        },
        onDownload = { subtitleId ->
            val item = currentItem
            val server = item?.serverId?.let(remoteSubtitleRegistry::serverById)
            if (item != null && server != null && remoteSubtitles.downloadingId == null) {
                remoteSubtitles = remoteSubtitles.copy(downloadingId = subtitleId, message = null)
                scope.launch {
                    remoteSubtitleRepository.downloadRemoteSubtitle(server, item.id, subtitleId)
                        .onSuccess {
                            remoteSubtitles = remoteSubtitles.copy(
                                downloadingId = null,
                                message = "字幕已下载，正在刷新播放轨道",
                            )
                            engine.retry()
                        }
                        .onFailure { error ->
                            remoteSubtitles = remoteSubtitles.copy(
                                downloadingId = null,
                                message = error.message ?: "字幕下载失败",
                            )
                        }
                }
            }
        },
    )
    // Selection is its own state, separate from position/buffering updates. Keying this on
    // the identifiers guarantees that a version-only change is handed back to the detail
    // page even when the replacement engine starts with a PlaybackState equal to the old one.
    LaunchedEffect(
        currentItem?.serverId,
        currentItem?.id,
        currentItem?.seriesId,
        currentItem?.versionId,
    ) {
        PlaybackSelection.update(currentItem)
    }
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
    val settledSkip = remember { mutableStateOf<Pair<String, PlaybackSegmentType>?>(null) }
    var skipCountdownSeconds by remember { mutableStateOf<Int?>(null) }
    val skipOccurrence = activeSegment?.let { segment ->
        currentItem?.id?.let { it to segment.type }
    }
    // A guest's playback is the host's to move. Arming here would fire a refused seek —
    // and its "当前由房主控制播放" toast — at every opening in the season.
    val watchGuest = watchState.connected && !watchState.canControl
    val skipArmed = skipOccurrence != null &&
        skipMode == SkipMode.Auto &&
        !watchGuest &&
        skipOccurrence != settledSkip.value
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
        settledSkip.value = skipOccurrence
        latestSkipSegment()
    }
    // 详情页 picked a 音轨 / 字幕 before this opened; apply it once the engine has published
    // what the file actually holds. Consumed rather than remembered — see PlaybackTrackRequest.
    val trackRequest = remember { GlobalContext.get().get<PlaybackTrackRequest>() }
    LaunchedEffect(currentItem?.id, state.audioTracks.size, state.subtitleTracks.size) {
        if (state.audioTracks.isEmpty() && state.subtitleTracks.isEmpty()) return@LaunchedEffect
        val requested = trackRequest.consume(currentItem?.id) ?: return@LaunchedEffect
        requested.audioLanguage?.let { language ->
            state.audioTracks.matchingLanguage(language)?.let(engine::selectAudioTrack)
        }
        when (val subtitle = requested.subtitleLanguage) {
            null -> Unit
            PlaybackTrackRequest.SUBTITLES_OFF -> engine.selectSubtitleTrack(EngineTrack.OFF)
            else -> state.subtitleTracks.matchingLanguage(subtitle)
                ?.let(engine::selectSubtitleTrack)
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
            if (watchState.connected) watchTogether.updateSyncDrift(0L)
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
        var bufferingSince = TimeSource.Monotonic.markNow()
        var wasBuffering = true
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
                        if (settling) {
                            if (timeline.paused && latestPlaybackState.playing) latestEngine.pause()
                            delay(GUEST_RECONCILE_TICK_MS)
                            continue
                        }
                        if (latestPlaybackState.buffering) {
                            if (!wasBuffering) bufferingSince = TimeSource.Monotonic.markNow()
                            wasBuffering = true
                            if (
                                bufferingSince.elapsedNow().inWholeMilliseconds >=
                                GUEST_BUFFER_RECOVERY_MS
                            ) {
                                latestEngine.retry()
                                bufferingSince = TimeSource.Monotonic.markNow()
                            }
                            if (timeline.paused && latestPlaybackState.playing) latestEngine.pause()
                            delay(GUEST_RECONCILE_TICK_MS)
                            continue
                        }
                        val recoveredFromBuffer = wasBuffering
                        wasBuffering = false

                        if (targetIndex != latestPlaybackState.currentIndex) {
                            latestEngine.selectItem(targetIndex)
                            awaitCorrection(positionMs = null, index = targetIndex)
                            delay(GUEST_RECONCILE_TICK_MS)
                            continue
                        }
                        val expected = timeline.expectedPositionMs(watchTogether.estimatedServerNow())
                        val diff = expected - position
                        watchTogether.updateSyncDrift(diff)
                        val desiredRate = when {
                            kotlin.math.abs(diff) >= HARD_SEEK_THRESHOLD_MS ||
                                (recoveredFromBuffer &&
                                    kotlin.math.abs(diff) >= POST_BUFFER_SEEK_THRESHOLD_MS) -> {
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
                        if (!timeline.paused && !latestPlaybackState.playing) {
                            if (onRemotePlayRequested()) latestEngine.play()
                        }
                    }
                }
                delay(GUEST_RECONCILE_TICK_MS)
            }
        } finally {
            mediaMatcher.reset()
            lastNominalRate?.let(latestEngine::setSpeed)
        }
    }
    LaunchedEffect(
        watchState.connected,
        watchState.reconnecting,
        watchState.localMediaAvailable,
        watchState.canControl,
        state.buffering,
        state.error,
        state.currentIndex,
    ) {
        if (watchState.connected && !watchState.reconnecting) {
            watchTogether.updatePlaybackStatus(
                ready = watchState.localMediaAvailable && !state.buffering && state.error == null,
                buffering = state.buffering,
                mediaAvailable = watchState.localMediaAvailable,
                syncDriftMs = if (watchState.isHost) 0L else null,
            )
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
    val latestActiveItems by rememberUpdatedState(activeItems)
    // One actor owns the entire reporting lifetime. Rebinding it serializes a version switch as
    // stop-old → start-new, while a tail append only extends its queue and leaves the current
    // encoding alone. Recreating two independent reporters cannot guarantee either property.
    val reporter =
        remember(playbackSink) {
            playbackSink?.let { PlaybackProgressReporter(activeItems, it) }
        }
    LaunchedEffect(state, activeItems, reporter) {
        reporter?.rebind(activeItems, state)
        reporter?.update(state)
        onPlaybackState(state, activeItems.getOrNull(state.currentIndex))
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
            val item = latestActiveItems.getOrNull(finalState.currentIndex)
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

    // Last resort of the fallback chain: exhaust decoder stacks for this file, then move to
    // the best untried file the same item owns. Both sets are bounded, so a title nothing can
    // play settles on an error instead of cycling through engines and versions forever.
    var versionsTried by remember(state.currentIndex) {
        mutableStateOf(setOfNotNull(currentItem?.versionId))
    }
    LaunchedEffect(state.currentIndex, currentItem?.versionId) {
        currentItem?.versionId?.let { versionsTried = versionsTried + it }
    }
    var enginesTried by remember(state.currentIndex, currentItem?.versionId) {
        mutableStateOf(setOf(kind))
    }
    var versionSwitchJob by remember { mutableStateOf<Job?>(null) }
    var versionSwitchNonce by remember { mutableIntStateOf(0) }
    var pendingVersionId by remember { mutableStateOf<String?>(null) }

    /**
     * Plays the current entry from a different file. The old server-side encoder is ended
     * before another engine is created, and every binding gets a fresh playback-session id.
     * That ordering prevents a late DELETE for A from killing a rapid A -> B -> A switch.
     */
    fun selectVersion(versionId: String, automaticRecovery: Boolean = false) {
        val switchState = latestState
        val item = latestActiveItems.getOrNull(switchState.currentIndex) ?: return
        val committedVersionId = versionChoices[item.id]?.id ?: item.versionId
        if (committedVersionId == versionId && pendingVersionId == null) return
        if (pendingVersionId == versionId) return
        val version = item.versions.firstOrNull { it.id == versionId } ?: return
        val freshVersion = version.withFreshPlaySession()
        val itemIndex = switchState.currentIndex
        val itemId = item.id
        val oldSessionId = item.playSessionId

        versionSwitchNonce++
        val operation = versionSwitchNonce
        versionSwitchJob?.cancel()
        pendingVersionId = versionId
        AppLog.info(
            category = "player",
            event = "version_switch_requested",
            message = "Playback media version switch requested",
            attributes = mapOf(
                "itemIndex" to itemIndex.toString(),
                "engine" to kind.name,
                "fromVersionId" to committedVersionId.orEmpty(),
                "toVersionId" to versionId,
            ),
        )

        versionSwitchJob = scope.launch {
            try {
                val cleanupSucceeded = if (oldSessionId.isBlank() || playbackSink == null) {
                    true
                } else {
                    try {
                        withTimeoutOrNull(5_000L) {
                            playbackSink.stopEncoding(oldSessionId)
                        } == true
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Throwable) {
                        AppLog.warning(
                            category = "player",
                            event = "version_switch_cleanup_failed",
                            message = "Old transcode cleanup threw before a version switch",
                            throwable = failure,
                            attributes = mapOf(
                                "itemIndex" to itemIndex.toString(),
                                "fromVersionId" to committedVersionId.orEmpty(),
                                "toVersionId" to versionId,
                                "playSessionId" to oldSessionId,
                            ),
                        )
                        false
                    }
                }

                if (operation != versionSwitchNonce) return@launch
                val latestItem = latestActiveItems.getOrNull(latestState.currentIndex)
                if (latestState.currentIndex != itemIndex || latestItem?.id != itemId) {
                    return@launch
                }
                if (!cleanupSucceeded) {
                    AppLog.warning(
                        category = "player",
                        event = "version_switch_cleanup_rejected",
                        message = "Old transcode could not be cleaned up; keeping current version",
                        attributes = mapOf(
                            "itemIndex" to itemIndex.toString(),
                            "fromVersionId" to committedVersionId.orEmpty(),
                            "toVersionId" to versionId,
                            "playSessionId" to oldSessionId,
                        ),
                    )
                    Toast.makeText(
                        context,
                        "切换版本失败：无法清理旧的服务器转码，请稍后重试",
                        Toast.LENGTH_LONG,
                    ).show()
                    return@launch
                }

                // Read the position only after cleanup succeeds. Until this point the old
                // engine remains attached, so a rejected/timeout cleanup is non-destructive.
                engine.pause()
                resume = itemIndex to engine.currentPositionMs()
                versionsTried = updatedVersionAttempts(
                    tried = versionsTried,
                    selected = versionId,
                    automaticRecovery = automaticRecovery,
                )
                versionChoices = versionChoices + (itemId to freshVersion)
                engineGeneration++
            } finally {
                if (operation == versionSwitchNonce) {
                    pendingVersionId = null
                    versionSwitchJob = null
                }
            }
        }
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

    LaunchedEffect(engine, kind, subtitleControls.offsetMs) {
        val applied = engine.setSubtitleOffsetMs(subtitleControls.offsetMs)
        if (!applied && subtitleControls.offsetMs != 0L && kind != PlayerEngine.Mpv) {
            switchEngine(PlayerEngine.Mpv)
        }
    }
    LaunchedEffect(engine, kind, subtitleControls.scale) {
        if (kind != PlayerEngine.Exo) {
            val applied = engine.setSubtitleScale(subtitleControls.scale)
            if (!applied && subtitleControls.scale != 1f && kind != PlayerEngine.Mpv) {
                switchEngine(PlayerEngine.Mpv)
            }
        }
    }
    LaunchedEffect(engine, scaleMode) {
        (engine as? MpvVideoEngine)?.setScaleMode(scaleMode)
        (engine as? MdkVideoEngine)?.setFill(scaleMode != VideoScaleMode.Fit)
    }
    LaunchedEffect(engine, state.subtitleTracks, pendingSubtitleLanguage) {
        val language = pendingSubtitleLanguage ?: return@LaunchedEffect
        state.subtitleTracks.matchingLanguage(language)?.let { trackId ->
            engine.selectSubtitleTrack(trackId)
            pendingSubtitleLanguage = null
        }
    }

    LaunchedEffect(
        state.fallbacksExhausted,
        state.automaticFallbackBlocked,
        state.currentIndex,
        kind,
        currentItem?.versionId,
    ) {
        if (!state.fallbacksExhausted || state.automaticFallbackBlocked) {
            return@LaunchedEffect
        }
        val triedEngines = enginesTried + kind
        enginesTried = triedEngines
        val nextEngine = PlayerEngine.selectable.firstOrNull { it !in triedEngines }
        if (nextEngine != null) {
            AppLog.info(
                category = "player",
                event = "engine_fallback",
                message = "Playback exhausted its streams; trying another engine",
                attributes = mapOf(
                    "from" to kind.name,
                    "to" to nextEngine.name,
                    "itemIndex" to state.currentIndex.toString(),
                ),
            )
            enginesTried = triedEngines + nextEngine
            switchEngine(nextEngine)
            return@LaunchedEffect
        }

        val nextVersion = currentItem?.nextFallbackVersionId(versionsTried)
            ?: return@LaunchedEffect
        AppLog.info(
            category = "player",
            event = "version_fallback",
            message = "Playback exhausted every engine; trying another media version",
            attributes = mapOf(
                "itemIndex" to state.currentIndex.toString(),
                "failedVersionId" to currentItem.versionId.orEmpty(),
                "nextVersionId" to nextVersion,
            ),
        )
        selectVersion(nextVersion, automaticRecovery = true)
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
            is ExoVideoEngine -> ExoSurface(
                engine = engine,
                scaleMode = scaleMode,
                subtitleScale = subtitleControls.scale,
                modifier = Modifier.fillMaxSize(),
            )
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
                filled = scaleMode != VideoScaleMode.Fit,
                onBack = onBack,
                onEnterPictureInPicture = onEnterPictureInPicture,
                onPlayPause = { playbackGate.togglePlayPause() },
                onRetry = { playbackGate.retry() },
                onSeek = playbackGate::seekTo,
                onSelectItem = playbackGate::selectItem,
                onPreviousItem = playbackGate::selectPrevious,
                onNextItem = playbackGate::selectNext,
                onRefreshEpisodes = onRefreshEpisodes,
                onSelectAudio = engine::selectAudioTrack,
                onSelectSubtitle = { id ->
                    val track = state.subtitleTracks.firstOrNull { it.id == id }
                    if (track?.requiresStyledRenderer == true && kind != PlayerEngine.Mpv) {
                        pendingSubtitleLanguage = track.language ?: track.label
                        switchEngine(PlayerEngine.Mpv)
                    } else {
                        engine.selectSubtitleTrack(id)
                    }
                },
                subtitleControls = subtitleControls,
                subtitleActions = SubtitleControlActions(
                    onOffset = { subtitleControls = subtitleControls.copy(offsetMs = it) },
                    onScale = { subtitleControls = subtitleControls.copy(scale = it) },
                ),
                remoteSubtitles = remoteSubtitles,
                remoteSubtitleActions = remoteSubtitleActions,
                onSpeed = { newSpeed -> playbackGate.setSpeed(newSpeed) },
                onToggleFill = {
                    scaleMode = scaleMode.next()
                    (engine as? MpvVideoEngine)?.setScaleMode(scaleMode)
                    (engine as? MdkVideoEngine)?.setFill(scaleMode != VideoScaleMode.Fit)
                    Toast.makeText(context, "画面：${scaleMode.label}", Toast.LENGTH_SHORT).show()
                },
                trickplay = currentItem?.trickplay,
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
                        // Cast receivers reliably support H.264/AAC HLS; an original MKV,
                        // ASS/PGS subtitle, or lossless audio track is far less portable.
                        val castUrl = item.transcodeUrl.ifBlank { item.url }
                        castManager.play(deviceId, castUrl, item.title)
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
                onSelectVersion = { versionId -> selectVersion(versionId) },
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
                    onCancelAuto = { settledSkip.value = skipOccurrence },
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
                    canControl = watchState.canControl,
                    controlMode = watchState.controlMode,
                    participantCount = watchState.participantCount,
                    participants = watchState.participants,
                    chatMessages = watchState.chatMessages,
                    chatError = watchState.chatError,
                    reactions = watchState.reactions,
                    chatPreviewEnabled = watchChatPreview,
                    chatDanmakuEnabled = watchChatDanmaku,
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
                    onSendChat = watchTogether::sendChat,
                    onRetryChat = watchTogether::retryChat,
                    onClearChatError = watchTogether::clearChatError,
                    onSetControlMode = watchTogether::setControlMode,
                    onSetModerator = watchTogether::setModerator,
                    onKickParticipant = watchTogether::kickParticipant,
                    onToggleChatDanmaku = {
                        watchTogetherPreferences.setChatDanmakuEnabled(!watchChatDanmaku)
                    },
                    onReact = { watchTogether.sendReaction(it) },
                    onReactionFinished = watchTogether::clearReaction,
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
internal fun List<EngineTrack>.matchingLanguage(language: String): String? {
    val wanted = language.trim().lowercase()
    if (wanted.isEmpty()) return null
    val wantedDisplay = languageDisplayName(wanted)
    return firstOrNull { it.language?.lowercase() == wanted }?.id
        ?: firstOrNull {
            languageDisplayName(it.language).equals(wantedDisplay, ignoreCase = true)
        }?.id
        ?: firstOrNull { it.language?.lowercase()?.startsWith(wanted.take(2)) == true }?.id
        ?: firstOrNull { it.label.contains(language, ignoreCase = true) }?.id
}

/**
 * Best remaining physical file for automatic recovery after every engine rejected the
 * selected one. Width and bitrate are the structured figures available in the player queue;
 * server order breaks a complete tie. The caller owns [tried] so this can never loop.
 */
internal fun PlayerMediaItem.nextFallbackVersionId(tried: Set<String>): String? =
    versions
        .sortedWith(
            compareByDescending<PlayerMediaVersion> { it.sourceWidth ?: 0 }
                .thenByDescending { it.sourceBitrateBps ?: 0 },
        )
        .firstOrNull { it.id !in tried }
        ?.id

/**
 * A deliberate version choice starts a new recovery budget. An automatic choice is part of
 * the existing chain and must retain every attempted file, otherwise A -> B could loop to A.
 */
internal fun updatedVersionAttempts(
    tried: Set<String>,
    selected: String,
    automaticRecovery: Boolean,
): Set<String> = if (automaticRecovery) tried + selected else setOf(selected)

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
private fun ExoSurface(
    engine: ExoVideoEngine,
    scaleMode: VideoScaleMode,
    subtitleScale: Float,
    modifier: Modifier = Modifier,
) {
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
            view.subtitleView?.setFractionalTextSize(0.0533f * subtitleScale.coerceIn(0.6f, 1.8f))
            view.resizeMode = when (scaleMode) {
                VideoScaleMode.Fit -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                VideoScaleMode.Fill -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                VideoScaleMode.Stretch -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            }
        },
        modifier = modifier,
    )
}
