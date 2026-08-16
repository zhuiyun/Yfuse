package com.yfuse.feature.player

import android.Manifest
import android.app.NotificationManager
import android.app.PictureInPictureParams
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Rect
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.yfuse.core.account.AccountAccessTokenSource
import com.yfuse.core.cast.CastCapability
import com.yfuse.core.cast.CastManager
import com.yfuse.core.data.DanmakuPreferences
import com.yfuse.core.data.DanmakuRepository
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.PlaybackPreferences
import com.yfuse.core.data.PlaybackRecoveryStore
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.data.SkipSegmentPreferences
import com.yfuse.core.data.ThemePreferences
import com.yfuse.core.data.UserAgentPreferences
import com.yfuse.core.data.WatchTogetherPreferences
import com.yfuse.core.data.dto.toMediaVersion
import com.yfuse.core.designsystem.AccentColor
import com.yfuse.core.designsystem.YfuseTheme
import com.yfuse.core.logging.AppLog
import com.yfuse.core.model.DecoderMode
import com.yfuse.core.model.PlaybackQuality
import com.yfuse.core.model.PlayerEngine
import com.yfuse.core.network.EmbyImages
import com.yfuse.core.network.EmbyStream
import com.yfuse.core.offline.OfflineMediaManager
import com.yfuse.core.playback.PlaybackDeviceCapabilitiesProvider
import com.yfuse.core.sync.WatchTogetherClient
import com.yfuse.core.sync.episodeWatchKey
import com.yfuse.core.sync.watchMatchKeys
import com.yfuse.core.util.lockOrientationOnCompactScreens
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext
import android.media.session.PlaybackState as PlatformPlaybackState

/**
 * Fullscreen playback lives in its own activity. Phones retain the landscape-first experience,
 * while Android 16 large screens stay adaptive and may rotate or resize freely.
 */
class PlayerActivity : ComponentActivity() {
    private var completedOfflineKey: String? = null

    companion object {
        internal const val NOTIFICATION_CHANNEL = "yfuse_playback"
        internal const val NOTIFICATION_ID = 2407
        private const val NOTIFICATION_PERMISSION_REQUEST = 2408
        internal const val ACTION_PREVIOUS = "com.yfuse.player.PREVIOUS"
        internal const val ACTION_PLAY_PAUSE = "com.yfuse.player.PLAY_PAUSE"
        internal const val ACTION_NEXT = "com.yfuse.player.NEXT"
        private const val ACTION_OPEN = "com.yfuse.player.OPEN"
        private const val PLAYING_EPISODE_REFRESH_INTERVAL_MS = 120_000L
        private const val PAUSED_EPISODE_REFRESH_INTERVAL_MS = 5 * 60_000L

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
            val request =
                PlayerLaunchRequest.create(
                    items = items,
                    startIndex = startIndex,
                    startPositionMs = startPositionMs,
                    engine = engine,
                    decoder = decoder,
                    autoNext = autoNext,
                    quality = quality,
                )
            val payload =
                PlayerLaunchIntentPayload.create(
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
    private lateinit var audioFocusController: PlayerAudioFocusController
    private var remoteCastManager: CastManager? = null
    private var sessionTitles: List<String> = emptyList()
    private val pictureInPicture = MutableStateFlow(false)
    private lateinit var mediaSession: MediaSession
    private lateinit var notificationController: PlayerNotificationController
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
    private lateinit var capabilityProvider: PlaybackDeviceCapabilitiesProvider
    private var episodeRefreshJob: Job? = null
    private var episodePollingJob: Job? = null
    private var capabilityMonitorJob: Job? = null
    private var outputRenegotiationJob: Job? = null
    private val launchViewModel by viewModels<PlayerLaunchViewModel>()
    private val mediaActionReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context?,
                intent: Intent?,
            ) {
                when (intent?.action) {
                    ACTION_PREVIOUS -> selectAdjacentPlayback(-1)
                    ACTION_PLAY_PAUSE -> togglePlaybackWithFocus()
                    ACTION_NEXT -> selectAdjacentPlayback(1)
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
    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent?,
    ): Boolean =
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN -> {
                val castManager = remoteCastManager
                val cast = castManager?.state?.value
                if (castManager != null && cast?.hasActiveSession == true) {
                    val currentVolume = cast.volume
                    if (
                        currentVolume != null &&
                        cast.capabilities.volume != CastCapability.Unsupported
                    ) {
                        val delta = if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) 0.05f else -0.05f
                        lifecycleScope.launch {
                            castManager.setVolume((currentVolume + delta).coerceIn(0f, 1f))
                        }
                    }
                } else {
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
                }
                volumeKeyPresses.value++
                true
            }

            else -> super.onKeyDown(keyCode, event)
        }

    /** Consumed alongside the down event, or the system panel appears on release. */
    override fun onKeyUp(
        keyCode: Int,
        event: KeyEvent?,
    ): Boolean =
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN -> true
            else -> super.onKeyUp(keyCode, event)
        }

    /**
     * Holds the screen awake while the film is running, and lets it time out once it is not.
     *
     * A film is watched rather than touched, so the window flag has to cover playback that
     * never sees an input event. Buffering counts as running: `playing` goes false through
     * startup and every seek, and blanking the screen because the stream stalled would be
     * the opposite of what is wanted.
     *
     * A pause is different. Nobody is looking at a still frame on purpose for long, and the
     * usual reason for pausing is that the viewer has gone to do something else — so the
     * screen should be allowed to time out normally, exactly as it would anywhere else in
     * the system. Resuming re-arms it here.
     */
    private fun applyScreenOnPolicy() {
        val awake = activeState.playing || activeState.buffering
        if (awake) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        lockOrientationOnCompactScreens(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE)
        super.onCreate(savedInstanceState)

        applyScreenOnPolicy()

        // Without this the hardware keys adjust whichever stream the system last considered
        // active — the ring volume, most often — so a user turning the volume up on a film
        // that has gone quiet changes nothing they can hear.
        volumeControlStream = AudioManager.STREAM_MUSIC
        audioManager = getSystemService(AudioManager::class.java)
        audioFocusController =
            PlayerAudioFocusController(
                audioManager = audioManager,
                isPlaying = { activeState.playing },
                onPause = { activeEngine?.pause() },
                onResume = { activeEngine?.play() },
            )

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }

        val launchPayload = PlayerLaunchIntentPayload.readFrom(intent)
        val launchResolution =
            resolvePlayerLaunch(
                retained = launchViewModel.request,
                payload = launchPayload,
            )
        if (launchResolution is PlayerLaunchResolution.Expired) {
            AppLog.warning(
                category = "feature.player",
                event = "launch_expired",
                message = "Player launch data was missing or expired",
                attributes =
                    buildMap {
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
        notificationController = PlayerNotificationController(this) { mediaSession }
        notificationController.createChannel()
        registerMediaActionReceiver()
        requestNotificationPermissionIfNeeded()

        val koin = GlobalContext.get()
        remoteCastManager = koin.get()
        embyRepository = koin.get()
        serverRegistry = koin.get()
        capabilityProvider = koin.get()
        val preferences =
            runCatching { koin.get<ThemePreferences>() }
                .onFailure {
                    AppLog.warning(
                        category = "feature.player",
                        event = "activity_preferences_unavailable",
                        message = "Player activity could not load theme preferences",
                        throwable = it,
                    )
                }.getOrNull()
        val danmakuPreferences = koin.get<DanmakuPreferences>()
        val skipSegmentPreferences = koin.get<SkipSegmentPreferences>()
        val danmakuRepository = koin.get<DanmakuRepository>()
        val playbackRecovery = koin.get<PlaybackRecoveryStore>()
        val offlineMediaManager = koin.get<OfflineMediaManager>()
        val playbackPreferences = koin.get<PlaybackPreferences>()
        val videoCacheBytes = playbackPreferences.videoCacheSize.value.bytes
        val customUserAgent = koin.get<UserAgentPreferences>().userAgent.value
        val watchTogether = koin.get<WatchTogetherClient>()
        val accountTokens = koin.get<AccountAccessTokenSource>()
        val watchTogetherPreferences = koin.get<WatchTogetherPreferences>()
        val playbackController =
            WatchGatedPlayback(
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
        capabilityMonitorJob =
            lifecycleScope.launch {
                capabilityProvider.revisions().drop(1).collect { revision ->
                    outputRenegotiationJob?.cancel()
                    outputRenegotiationJob =
                        lifecycleScope.launch {
                            delay(500L)
                            renegotiateCurrentPlayback(revision)
                        }
                }
            }
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
        val playbackSinkFor =
            runCatching {
                val registry = koin.get<ServerRegistry>()
                val coordinator = koin.get<PlaybackReportingCoordinator>()
                val resolver: (PlaybackReportingTarget) -> PlaybackEventSink? = { target ->
                    when (target) {
                        is PlaybackReportingTarget.SavedServer ->
                            target.id.takeIf { registry.serverById(it) != null }
                        PlaybackReportingTarget.DefaultServer -> registry.defaultServer?.id
                        PlaybackReportingTarget.Disabled -> null
                    }?.let(coordinator::sinkFor)
                }
                resolver
            }.onFailure {
                AppLog.warning(
                    category = "feature.player",
                    event = "playback_reporting_unavailable",
                    message = "Server playback reporting could not be initialized",
                    throwable = it,
                )
            }.getOrElse {
                { _: PlaybackReportingTarget -> null }
            }

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
                    initialQuality = quality,
                    autoQualityDowngrade = playbackPreferences.autoQualityDowngrade.value,
                    qualityLocked = playbackPreferences.qualityLocked.value,
                    playbackPreferences = playbackPreferences,
                    onQualityChanged = { selected, serverId ->
                        preferences?.setQuality(selected)
                        serverId?.let { playbackPreferences.rememberQuality(it, selected) }
                    },
                    inPictureInPicture = inPictureInPicture,
                    playbackSinkFor = playbackSinkFor,
                    danmakuPreferences = danmakuPreferences,
                    skipSegmentPreferences = skipSegmentPreferences,
                    volumeKeyPresses = volumeKeyPresses,
                    danmakuRepository = danmakuRepository,
                    playbackRecovery = playbackRecovery,
                    customUserAgent = customUserAgent,
                    videoCacheBytes = videoCacheBytes,
                    watchTogether = watchTogether,
                    accountTokens = accountTokens,
                    watchTogetherPreferences = watchTogetherPreferences,
                    playbackGate = playbackController,
                    onEngineAttached = { engine -> activeEngine = engine },
                    onEngineDetached = { engine ->
                        if (activeEngine === engine) activeEngine = null
                    },
                    onPlaybackState = { state, item ->
                        activeState = state
                        applyScreenOnPolicy()
                        if (state.ended && item?.serverId != null) {
                            val completedKey = "${item.serverId}#${item.id}"
                            if (completedOfflineKey != completedKey) {
                                completedOfflineKey = completedKey
                                offlineMediaManager.onPlaybackCompleted(item.serverId, item.id)
                            }
                        } else if (!state.ended) {
                            completedOfflineKey = null
                        }
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
                    attributes =
                        mapOf(
                            "itemCount" to
                                replacement.request.items.size
                                    .toString(),
                        ),
                )
                recreate()
            }
            is PlayerLaunchResolution.Expired -> {
                AppLog.warning(
                    category = "feature.player",
                    event = "replacement_launch_expired",
                    message = "Replacement player launch data was missing or expired",
                )
                Toast
                    .makeText(
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
        episodePollingJob =
            lifecycleScope.launch {
                while (isActive) {
                    delay(
                        if (activeState.playing) {
                            PLAYING_EPISODE_REFRESH_INTERVAL_MS
                        } else {
                            PAUSED_EPISODE_REFRESH_INTERVAL_MS
                        },
                    )
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
                PictureInPictureParams
                    .Builder()
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
        capabilityMonitorJob?.cancel()
        outputRenegotiationJob?.cancel()
        ActivePlayback.clear()
        stopPlaybackKeepAliveService()
        abandonAudioFocus()
        if (::notificationController.isInitialized) {
            notificationController.cancel()
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
            PlaybackKeepAliveService.requestStop(this)
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
            PictureInPictureParams
                .Builder()
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

    /** Re-runs PlaybackInfo after Android reports a new display or routed audio device. */
    private suspend fun renegotiateCurrentPlayback(capabilityRevision: Long) {
        if (remoteCastManager?.state?.value?.hasActiveSession == true) return
        val snapshot = playbackItems.value
        val index = activeState.currentIndex
        val item = snapshot.getOrNull(index) ?: return
        val server = item.serverId?.let(serverRegistry::serverById) ?: return
        val sourceId = item.activeVersion?.id ?: item.versionId
        val positionMs = (activeEngine?.currentPositionMs() ?: activeState.positionMs).coerceAtLeast(0L)
        val requestedSessionId = EmbyStream.newPlaySessionId()

        embyRepository
            .playbackInfo(
                server = server,
                itemId = item.id,
                mediaSourceId = sourceId,
                startPositionTicks = positionMs.toEmbyTicks(),
                playSessionId = requestedSessionId,
            ).onSuccess { playbackInfo ->
                if (
                    activeState.currentIndex != index ||
                    playbackItems.value.getOrNull(index)?.id != item.id
                ) {
                    return@onSuccess
                }
                val versions =
                    playbackInfo.MediaSources
                        .mapIndexed { ordinal, source ->
                            source.toMediaVersion(fallbackId = item.id, ordinal = ordinal)
                        }.toPlayerMediaVersions(
                            baseUrl = server.baseUrl,
                            itemId = item.id,
                            token = server.accessToken,
                            negotiatedPlaySessionId =
                                playbackInfo.PlaySessionId
                                    ?.takeIf(String::isNotBlank)
                                    ?: requestedSessionId,
                            localCleartextConfirmed = server.localCleartextConfirmed,
                        )
                val selected =
                    versions.firstOrNull { version -> version.id == sourceId }
                        ?: versions.firstOrNull()
                        ?: return@onSuccess
                val refreshed =
                    item.copy(
                        url = selected.url,
                        transcodeUrl = selected.transcodeUrl,
                        fallbackTranscodeUrl = selected.fallbackTranscodeUrl,
                        versions = versions,
                        versionId = selected.id,
                        playSessionId = selected.playSessionId,
                        playMethod = selected.playMethod,
                        forcedTranscodeReason = null,
                        trickplay = item.trickplay.takeIf { selected.id == sourceId },
                    )
                playbackItems.value =
                    playbackItems.value.toMutableList().apply { set(index, refreshed) }
                queueResume.value = index to positionMs
                queueRevision.value++
                AppLog.info(
                    category = "player.capabilities",
                    event = "server_playback_renegotiated",
                    message = "PlaybackInfo was refreshed after the output route changed",
                    attributes =
                        mapOf(
                            "revision" to capabilityRevision.toString(),
                            "itemIndex" to index.toString(),
                            "playMethod" to selected.playMethod.name,
                            "mediaSourceId" to selected.id,
                        ),
                )
            }.onFailure { error ->
                AppLog.warning(
                    category = "player.capabilities",
                    event = "server_playback_renegotiation_failed",
                    message = "PlaybackInfo refresh failed after the output route changed",
                    throwable = error,
                    attributes =
                        mapOf(
                            "revision" to capabilityRevision.toString(),
                            "itemIndex" to index.toString(),
                        ),
                )
            }
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

        episodeRefreshJob =
            lifecycleScope.launch {
                val seriesDetail = embyRepository.itemDetail(server, seriesId).getOrNull()
                val seriesProviderIds = seriesDetail?.providerIds.orEmpty()
                val seriesPosterUrl =
                    EmbyImages.primary(
                        baseUrl = server.baseUrl,
                        itemId = seriesDetail?.posterItemId ?: seriesId,
                        tag = seriesDetail?.posterTag,
                        maxHeight = 360,
                        accessToken = server.accessToken,
                    )
                val episodes =
                    embyRepository
                        .episodes(
                            server,
                            seriesId,
                            null,
                            includeMediaSources = true,
                        ).onFailure { error ->
                            AppLog.warning(
                                category = "player.queue",
                                event = "episode_refresh_failed",
                                message = "Player episode queue refresh failed",
                                throwable = error,
                                attributes = mapOf("serverId" to server.id),
                            )
                        }.getOrNull()
                        .orEmpty()
                if (episodes.isEmpty()) return@launch

                val existing = playbackItems.value.associateBy(PlayerMediaItem::id)
                val refreshed =
                    episodes.map { episode ->
                        val title =
                            listOfNotNull(
                                episode.indexNumber?.let { "第 $it 集" },
                                episode.name.takeIf { it.isNotBlank() },
                            ).joinToString("  ")
                        val stillUrl =
                            EmbyImages.primary(
                                server.baseUrl,
                                episode.id,
                                episode.primaryTag,
                                maxHeight = 240,
                                accessToken = server.accessToken,
                            )
                        val progress =
                            when {
                                episode.played -> 1f
                                else -> episode.playedPercentage?.let { (it / 100.0).toFloat() }
                            }
                        existing[episode.id]?.copy(
                            title = title,
                            playbackSegments = episode.playbackSegments,
                            seasonNumber = episode.seasonNumber,
                            episodeNumber = episode.indexNumber,
                            stillUrl = stillUrl,
                            posterUrl = seriesPosterUrl,
                            progress = progress,
                            caption = episode.indexNumber?.let { "第 $it 集" },
                        ) ?: run {
                            val versions =
                                episode.versions.toPlayerMediaVersions(
                                    baseUrl = server.baseUrl,
                                    itemId = episode.id,
                                    token = server.accessToken,
                                )
                            val selected = versions.firstOrNull()
                            val unqualified =
                                if (selected == null) {
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
                                fallbackTranscodeUrl =
                                    selected?.fallbackTranscodeUrl
                                        ?: requireNotNull(unqualified).progressiveTranscode,
                                playSessionId =
                                    selected?.playSessionId
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
                                watchKey =
                                    episodeWatchKey(
                                        ownProviderIds = episode.providerIds,
                                        seriesProviderIds = seriesProviderIds,
                                        seasonNumber = episode.seasonNumber,
                                        episodeNumber = episode.indexNumber,
                                        fallbackId = episode.id,
                                    ),
                                matchKeys =
                                    watchMatchKeys(
                                        ownProviderIds = episode.providerIds,
                                        seriesProviderIds = seriesProviderIds,
                                        seasonNumber = episode.seasonNumber,
                                        episodeNumber = episode.indexNumber,
                                        fallbackId = episode.id,
                                    ),
                                stillUrl = stillUrl,
                                posterUrl = seriesPosterUrl,
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
        mediaSession =
            MediaSession(this, "YfusePlayer").apply {
                setCallback(
                    object : MediaSession.Callback() {
                        override fun onPlay() {
                            val castManager = remoteCastManager
                            if (castManager?.state?.value?.hasActiveSession == true) {
                                lifecycleScope.launch { castManager.resume() }
                                return
                            }
                            if (ensureAudioFocus()) {
                                startPlaybackKeepAliveService(fromUserAction = true)
                                playbackGate?.play()
                            }
                        }

                        override fun onPause() {
                            val castManager = remoteCastManager
                            if (castManager?.state?.value?.hasActiveSession == true) {
                                lifecycleScope.launch { castManager.pause() }
                            } else {
                                playbackGate?.pause()
                            }
                        }

                        override fun onSeekTo(pos: Long) {
                            val castManager = remoteCastManager
                            if (castManager?.state?.value?.hasActiveSession == true) {
                                lifecycleScope.launch { castManager.seekTo(pos) }
                            } else {
                                playbackGate?.seekTo(pos)
                            }
                        }

                        override fun onSkipToNext() {
                            selectAdjacentPlayback(1)
                        }

                        override fun onSkipToPrevious() {
                            selectAdjacentPlayback(-1)
                        }
                    },
                )
                isActive = true
            }
    }

    private fun registerMediaActionReceiver() {
        val filter =
            IntentFilter().apply {
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
        val params =
            PictureInPictureParams
                .Builder()
                .setAspectRatio(Rational(16, 9))
                .apply {
                    videoBounds?.let(::setSourceRectHint)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        setAutoEnterEnabled(activeState.playing)
                    }
                }.build()
        setPictureInPictureParams(params)
    }

    private fun updateMediaSession(state: PlaybackState) {
        val remoteActive = remoteCastManager?.state?.value?.hasActiveSession == true
        if (remoteActive) {
            abandonAudioFocus()
        } else if (state.playing && !ensureAudioFocus()) {
            activeEngine?.pause()
        } else if (state.ended || state.error != null) {
            abandonAudioFocus()
        }
        val actions =
            PlatformPlaybackState.ACTION_PLAY or
                PlatformPlaybackState.ACTION_PAUSE or
                PlatformPlaybackState.ACTION_PLAY_PAUSE or
                PlatformPlaybackState.ACTION_SEEK_TO or
                PlatformPlaybackState.ACTION_SKIP_TO_NEXT or
                PlatformPlaybackState.ACTION_SKIP_TO_PREVIOUS
        val platformState =
            when {
                state.error != null -> PlatformPlaybackState.STATE_ERROR
                state.ended -> PlatformPlaybackState.STATE_STOPPED
                state.buffering -> PlatformPlaybackState.STATE_BUFFERING
                state.playing -> PlatformPlaybackState.STATE_PLAYING
                else -> PlatformPlaybackState.STATE_PAUSED
            }
        val builder =
            PlatformPlaybackState
                .Builder()
                .setActions(actions)
                .setState(platformState, state.positionMs, state.speed)
        state.error?.let(builder::setErrorMessage)
        mediaSession.setPlaybackState(builder.build())
        mediaSession.setMetadata(
            MediaMetadata
                .Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, sessionTitles.getOrNull(state.currentIndex).orEmpty())
                .putLong(MediaMetadata.METADATA_KEY_DURATION, state.durationMs)
                .build(),
        )
        notificationController.update(state, sessionTitles)
    }

    private fun togglePlaybackWithFocus() {
        val castManager = remoteCastManager
        if (castManager?.state?.value?.hasActiveSession == true) {
            lifecycleScope.launch {
                if (castManager.state.value.lastRemoteWasPlaying) {
                    castManager.pause()
                } else {
                    castManager.resume()
                }
            }
            return
        }
        if (activeState.playing) {
            playbackGate?.pause()
        } else if (ensureAudioFocus()) {
            startPlaybackKeepAliveService(fromUserAction = true)
            playbackGate?.play()
        }
    }

    private fun selectAdjacentPlayback(offset: Int) {
        val castManager = remoteCastManager
        val cast = castManager?.state?.value
        if (castManager == null || cast?.hasActiveSession != true) {
            if (offset < 0) playbackGate?.selectPrevious() else playbackGate?.selectNext()
            return
        }
        val targetIndex = activeState.currentIndex + offset
        val item = playbackItems.value.getOrNull(targetIndex) ?: return
        val deviceId = cast.activeDeviceId ?: return
        val mediaUrl = item.transcodeUrl.ifBlank { item.url }
        lifecycleScope.launch {
            if (castManager.play(deviceId, mediaUrl, item.title, 0L)) {
                activeEngine?.selectItem(targetIndex)
                activeEngine?.pause()
            }
        }
    }

    /**
     * Requests the playback foreground service once per player session.
     *
     * Engine state is emitted continuously, including while this activity is in PiP or stopped.
     * Calling `startForegroundService` for every emission eventually makes Android treat one as a
     * background start and throw `ForegroundServiceStartNotAllowedException`. A rejected request
     * is deferred until the activity becomes visible again; notification/media-session actions
     * get one immediate retry because they are explicit user actions. Once started, the service is
     * retained across pause/buffer/engine-handover states and is stopped by the player lifecycle;
     * otherwise a fast Exo -> MPV handover can stop it before Android delivers Service.onCreate.
     */
    private fun startPlaybackKeepAliveService(fromUserAction: Boolean = false) {
        if (playbackKeepAliveRequested) return
        if (playbackKeepAliveStartDeferred && !activityStarted && !fromUserAction) return

        try {
            PlaybackKeepAliveService.prepareStart()
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
        PlaybackKeepAliveService.requestStop(this)
        playbackKeepAliveRequested = false
        playbackKeepAliveStartDeferred = false
    }

    private fun ensureAudioFocus(): Boolean = audioFocusController.ensure()

    private fun abandonAudioFocus() {
        if (::audioFocusController.isInitialized) audioFocusController.abandon()
    }
}

private fun Long.toEmbyTicks(): Long =
    coerceIn(0L, Long.MAX_VALUE / EMBY_TICKS_PER_MILLISECOND) * EMBY_TICKS_PER_MILLISECOND

private const val EMBY_TICKS_PER_MILLISECOND = 10_000L
