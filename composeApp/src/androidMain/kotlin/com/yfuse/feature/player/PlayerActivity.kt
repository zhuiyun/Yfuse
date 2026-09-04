package com.yfuse.feature.player

import android.annotation.SuppressLint
import android.Manifest
import android.app.NotificationManager
import android.app.PictureInPictureParams
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Rect
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
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
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.extensions.coroutines.states
import com.yfuse.core.account.AccountAccessTokenSource
import com.yfuse.core.cast.CastCapability
import com.yfuse.core.cast.CastManager
import com.yfuse.core.data.DanmakuPreferences
import com.yfuse.core.data.DanmakuRepository
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.PlaybackPreferences
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.data.SkipSegmentPreferences
import com.yfuse.core.data.ThemePreferences
import com.yfuse.core.data.UserAgentPreferences
import com.yfuse.core.data.WatchTogetherPreferences
import com.yfuse.core.data.dto.toMediaVersion
import com.yfuse.core.data.preferredVersion
import com.yfuse.core.designsystem.AccentColor
import com.yfuse.core.designsystem.YfuseTheme
import com.yfuse.core.logging.AppLog
import com.yfuse.core.model.DecoderMode
import com.yfuse.core.model.PlayerEngine
import com.yfuse.core.network.EmbyImages
import com.yfuse.core.network.EmbyStream
import com.yfuse.core.offline.OfflineMediaManager
import com.yfuse.core.playback.PlaybackDeviceCapabilitiesProvider
import com.yfuse.core.sync.WatchTogetherClient
import com.yfuse.core.sync.episodeWatchKey
import com.yfuse.core.sync.watchKey
import com.yfuse.core.sync.watchMatchKeys
import com.yfuse.core2.api.YPlayer
import com.yfuse.tv.integration.CastConnectReceiverBridge
import com.yfuse.tv.player.TvMediaSessionActions
import com.yfuse.tv.player.TvMediaSessionAdapter
import com.yfuse.tv.player.TvMediaSessionState
import com.yfuse.tv.player.TvPlaybackActions
import com.yfuse.tv.player.TvPlayerChromeController
import com.yfuse.tv.player.TvRemoteInputController
import com.yfuse.tv.player.isTelevisionDevice
import com.yfuse.tv.player.withoutServerTranscodeForTv
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

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
        private const val EPISODE_REFRESH_COOLDOWN_MS = 5 * 60_000L
        private const val EPISODE_REFRESH_NEAR_END_MS = 5 * 60_000L

        fun intent(
            context: Context,
            items: List<PlayerMediaItem>,
            startIndex: Int,
            startPositionMs: Long,
            engine: PlayerEngine,
            decoder: DecoderMode,
            autoNext: Boolean,
            startPlaybackRequested: Boolean = true,
        ): Intent {
            val request =
                PlayerLaunchRequest.create(
                    items = items,
                    startIndex = startIndex,
                    startPositionMs = startPositionMs,
                    engine = engine,
                    decoder = decoder,
                    autoNext = autoNext,
                    startPlaybackRequested = startPlaybackRequested,
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

        fun pendingIntent(
            context: Context,
            store: Store<PlayerIntent, PlayerState, Nothing>,
            startPlaybackRequested: Boolean,
        ): Intent {
            val launchId =
                PendingPlayerLaunchRegistry.register(
                    PendingPlayerLaunch(
                        store = store,
                        startPlaybackRequested = startPlaybackRequested,
                    ),
                )
            return Intent(context, PlayerActivity::class.java)
                .also { PendingPlayerLaunchRegistry.writeTo(it, launchId) }
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

        internal fun discardPendingLaunch(intent: Intent) {
            PendingPlayerLaunchRegistry.discard(PendingPlayerLaunchRegistry.readFrom(intent))
        }
    }

    private var activePlayer: YPlayer? = null
    private var activeQueueAppender: ((List<PlayerMediaItem>) -> Boolean)? = null
    private var playbackGate: WatchGatedPlayback? = null
    private var activeState = PlaybackState()
    private lateinit var audioManager: AudioManager
    private lateinit var audioFocusController: PlayerAudioFocusController
    private var remoteCastManager: CastManager? = null
    private var sessionTitles: List<String> = emptyList()
    private val pictureInPicture = MutableStateFlow(false)
    private lateinit var mediaSessionAdapter: TvMediaSessionAdapter
    private lateinit var notificationController: PlayerNotificationController
    private val tvChromeController = TvPlayerChromeController()
    private var tvRemoteInputController: TvRemoteInputController? = null
    private var televisionDevice = false
    private var mediaReceiverRegistered = false
    private var videoBounds: Rect? = null
    private var pipWasVisible = false
    private var stopRequested = false
    private var activityStarted = false
    private var activityHasStarted = false
    private var lifecyclePauseRequested = false
    private var screenStateReceiverRegistered = false
    private var playbackKeepAliveRequested = false
    private var playbackKeepAliveStartDeferred = false
    private val playbackItems = MutableStateFlow<List<PlayerMediaItem>>(emptyList())
    private val queueResume = MutableStateFlow(0 to 0L)
    private val queueRevision = MutableStateFlow(0L)
    private lateinit var embyRepository: EmbyRepository
    private lateinit var serverRegistry: ServerRegistry
    private lateinit var playbackPreferences: PlaybackPreferences
    private lateinit var capabilityProvider: PlaybackDeviceCapabilitiesProvider
    private var episodeRefreshJob: Job? = null
    private var lastEpisodeRefreshElapsedMs = 0L
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

    private val screenStateReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context?,
                intent: Intent?,
            ) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> pausePlaybackForLifecycle("screen_off")
                    Intent.ACTION_SCREEN_ON -> lifecyclePauseRequested = false
                    AudioManager.ACTION_AUDIO_BECOMING_NOISY -> pausePlaybackForNoisyOutput()
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

    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Authored DVD/Blu-ray menus own the complete remote while interactive. Their Compose
        // effect routes D-pad and Back after normal dispatch, so TV transport must not pre-empt it.
        if (
            televisionDevice &&
            !ActiveDiscNavigation.menuActive &&
            tvRemoteInputController?.dispatch(event) == true
        ) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

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
        // PlayerActivity declares a fixed landscape orientation in the manifest. This rotates only
        // this Activity and never writes ACCELEROMETER_ROTATION or USER_ROTATION, so leaving the
        // player restores the user's unchanged system rotation preference.
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
                onPause = { activePlayer?.pause() },
                onResume = { activePlayer?.play() },
            )
        televisionDevice = isTelevisionDevice(this)
        if (televisionDevice) {
            tvRemoteInputController =
                TvRemoteInputController(
                    chrome = tvChromeController,
                    playback =
                        TvPlaybackActions(
                            currentPositionMs = {
                                activePlayer?.currentPositionMs() ?: activeState.positionMs
                            },
                            durationMs = { activeState.durationMs },
                            togglePlayPause = ::togglePlaybackWithFocus,
                            play = ::requestPlaybackStart,
                            pause = ::requestPlaybackPause,
                            seekTo = ::seekPlaybackTo,
                            previous = { selectAdjacentPlayback(-1) },
                            next = { selectAdjacentPlayback(1) },
                        ),
                    nowMs = SystemClock::uptimeMillis,
                )
        }

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }

        if (launchViewModel.request == null) {
            val retainedPending = launchViewModel.pending
            val pending =
                retainedPending
                    ?: PendingPlayerLaunchRegistry.consume(
                        PendingPlayerLaunchRegistry.readFrom(intent),
                    )
            if (pending != null) {
                launchViewModel.pending = pending
                showPendingPlayer(pending)
                return
            }
            if (PendingPlayerLaunchRegistry.readFrom(intent) != null) {
                AppLog.warning(
                    category = "feature.player",
                    event = "pending_launch_expired",
                    message = "Pending player preparation was missing or expired",
                )
                Toast.makeText(this, "播放会话已过期，请重新打开", Toast.LENGTH_SHORT).show()
                finish()
                return
            }
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
        initializePlayer(launchRequest)
    }

    private fun showPendingPlayer(pending: PendingPlayerLaunch) {
        val accent =
            runCatching { GlobalContext.get().get<ThemePreferences>().accent.value }
                .getOrDefault(AccentColor.Blue)
        setContent {
            val state by pending.store.states.collectAsState(pending.store.state)
            YfuseTheme(dark = true, accent = accent) {
                PlayerPreparationContent(
                    state = state,
                    onRetry = { pending.store.accept(PlayerIntent.Retry) },
                    onBack = ::finish,
                )
            }
        }
        lifecycleScope.launch {
            val state = pending.store.states.first { it.items.isNotEmpty() }
            val koin = GlobalContext.get()
            val registry = runCatching { koin.get<ServerRegistry>() }.getOrNull()
            val preparedItems =
                runCatching {
                    val localPrepared =
                        prepareNativeLocalBluRayRoute(
                            state.items,
                            state.startIndex,
                            this@PlayerActivity,
                        )
                    prepareNativeRemoteBluRayRoutes(localPrepared, state.startIndex, registry)
                }.onFailure { error ->
                    AppLog.warning(
                        category = "feature.player",
                        event = "native_disc_route_preparation_failed",
                        message = "Optional native disc route preparation failed; using resolved source",
                        throwable = error,
                    )
                }.getOrDefault(state.items)
            PlaybackSelection.update(preparedItems.getOrNull(state.startIndex))
            val preferences = runCatching { koin.get<ThemePreferences>() }.getOrNull()
            val request =
                PlayerLaunchRequest.create(
                    items = preparedItems,
                    startIndex = state.startIndex,
                    startPositionMs = state.startPositionMs,
                    engine =
                        offlineSubtitlePlaybackEngine(
                            preferred = preferences?.engine?.value ?: PlayerEngine.Exo,
                            items = preparedItems,
                        ),
                    decoder = preferences?.decoder?.value ?: DecoderMode.Hardware,
                    autoNext = preferences?.autoNext?.value ?: true,
                    startPlaybackRequested = pending.startPlaybackRequested,
                )
            launchViewModel.request = request
            launchViewModel.pending = null
            pending.store.dispose()
            AppLog.info(
                category = "feature.player",
                event = "preparation_completed_in_activity",
                message = "Playback preparation completed inside player activity",
                attributes = mapOf("itemCount" to preparedItems.size.toString()),
            )
            initializePlayer(request)
        }
    }

    private fun initializePlayer(launchRequest: PlayerLaunchRequest) {
        launchViewModel.request = launchRequest
        val items =
            if (televisionDevice) {
                launchRequest.items.withoutServerTranscodeForTv()
            } else {
                launchRequest.items
            }
        val initialEngine = launchRequest.engine
        val decoderMode = launchRequest.decoder
        val autoNext = launchRequest.autoNext
        val startPlaybackRequested = launchRequest.startPlaybackRequested
        val retainedResume = launchViewModel.resume
        val initialStartIndex = retainedResume?.first ?: launchRequest.startIndex
        val initialStartPositionMs = retainedResume?.second ?: launchRequest.startPositionMs
        playbackItems.value = items
        pictureInPicture.value = isInPictureInPictureMode
        pipWasVisible = isInPictureInPictureMode
        sessionTitles = items.map { it.title }
        createMediaSession()
        if (televisionDevice) CastConnectReceiverBridge.onNewIntent(intent)
        notificationController = PlayerNotificationController(this) { mediaSessionAdapter.session }
        notificationController.createChannel()
        registerMediaActionReceiver()
        registerScreenStateReceiver()
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
        val offlineMediaManager = koin.get<OfflineMediaManager>()
        playbackPreferences = koin.get()
        val videoCacheBytes = playbackPreferences.videoCacheSize.value.bytes
        val yCoreBufferTargetUs = playbackPreferences.yCoreBufferDuration.value.targetDurationUs
        val customUserAgent = koin.get<UserAgentPreferences>().userAgent.value
        val watchTogether = koin.get<WatchTogetherClient>()
        val accountTokens = koin.get<AccountAccessTokenSource>()
        val watchTogetherPreferences = koin.get<WatchTogetherPreferences>()
        val playbackController =
            WatchGatedPlayback(
                watchTogether = watchTogether,
                items = { playbackItems.value },
                player = { activePlayer },
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
                    playbackPreferences = playbackPreferences,
                    inPictureInPicture = inPictureInPicture,
                    playbackSinkFor = playbackSinkFor,
                    danmakuPreferences = danmakuPreferences,
                    skipSegmentPreferences = skipSegmentPreferences,
                    volumeKeyPresses = volumeKeyPresses,
                    danmakuRepository = danmakuRepository,
                    customUserAgent = customUserAgent,
                    videoCacheBytes = videoCacheBytes,
                    yCoreBufferTargetUs = yCoreBufferTargetUs,
                    watchTogether = watchTogether,
                    accountTokens = accountTokens,
                    watchTogetherPreferences = watchTogetherPreferences,
                    playbackGate = playbackController,
                    onPlayerAttached = { player, appendItems ->
                        activePlayer = player
                        activeQueueAppender = appendItems
                    },
                    onPlayerDetached = { player ->
                        if (activePlayer === player) {
                            activePlayer = null
                            activeQueueAppender = null
                        }
                    },
                    onPlaybackState = { state, item ->
                        activeState = state
                        applyScreenOnPolicy()
                        if (
                            state.playing &&
                            state.hasNext &&
                            state.remainingMs in 1L..EPISODE_REFRESH_NEAR_END_MS
                        ) {
                            refreshEpisodes()
                        }
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
                        if (
                            (state.playing || state.buffering) &&
                            (
                                !isScreenInteractive() ||
                                    activityHasStarted &&
                                    !activityStarted &&
                                    !isInPictureInPictureMode
                            )
                        ) {
                            pausePlaybackForLifecycle("state_resumed_while_hidden")
                        } else if (state.playing) {
                            startPlaybackKeepAliveService()
                        }
                    },
                    onVideoBounds = { bounds ->
                        videoBounds = bounds
                        updatePictureInPictureParams()
                    },
                    onBack = ::closePlayerAndReturn,
                    onEnterPictureInPicture = ::enterPlayerPictureInPicture,
                    onRefreshEpisodes = { refreshEpisodes(force = true) },
                    onRemotePlayRequested = ::ensureAudioFocus,
                    remoteChrome = tvChromeController.takeIf { televisionDevice },
                    startPlaybackRequested = startPlaybackRequested,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (televisionDevice) CastConnectReceiverBridge.onNewIntent(intent)
        // Notification taps only bring this live instance forward. They deliberately carry no
        // launch token and must never restart playback or consume registry state.
        if (intent.action == ACTION_OPEN) return

        PendingPlayerLaunchRegistry.readFrom(intent)?.let { pendingId ->
            val replacement = PendingPlayerLaunchRegistry.consume(pendingId)
            if (replacement == null) {
                Toast.makeText(this, "新的播放会话已过期，继续当前播放", Toast.LENGTH_SHORT).show()
                return
            }
            launchViewModel.pending?.store?.dispose()
            launchViewModel.pending = replacement
            launchViewModel.request = null
            launchViewModel.resume = null
            setIntent(intent)
            stopRequested = true
            AppLog.info(
                category = "feature.player",
                event = "pending_launch_replaced",
                message = "Active player is being replaced before playback preparation",
            )
            recreate()
            return
        }

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
        activityHasStarted = true
        PlayerForegroundRegistry.setVisible(true)
        if (isScreenInteractive()) lifecyclePauseRequested = false
        if (activeState.playing) startPlaybackKeepAliveService()
        refreshEpisodes()
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
                    .setAspectRatio(activePictureInPictureAspectRatio())
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
        // A picture-in-picture player is still on screen and still streaming, whether or not this
        // callback ran for it. Keeping the flag set is what stops MainActivity - restarted
        // underneath the PiP window - from resuming health probes and sync over the same link.
        if (!isInPictureInPictureMode) PlayerForegroundRegistry.setVisible(false)
        episodeRefreshJob?.cancel()
        episodeRefreshJob = null
        super.onStop()
        when (
            playerStopAction(
                screenInteractive = isScreenInteractive(),
                inPictureInPicture = isInPictureInPictureMode,
                pictureInPictureWasVisible = pipWasVisible,
                changingConfigurations = isChangingConfigurations,
            )
        ) {
            PlayerStopAction.Pause -> pausePlaybackForLifecycle("activity_stopped")
            PlayerStopAction.KeepPlaying,
            PlayerStopAction.IgnoreConfigurationChange,
            -> Unit
            PlayerStopAction.FinishClosedPictureInPicture -> stopPlaybackAndFinish()
        }
    }

    override fun onDestroy() {
        PlayerForegroundRegistry.setVisible(false)
        episodeRefreshJob?.cancel()
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
        if (screenStateReceiverRegistered) {
            runCatching { unregisterReceiver(screenStateReceiver) }
            screenStateReceiverRegistered = false
        }
        if (::mediaSessionAdapter.isInitialized) {
            if (televisionDevice) CastConnectReceiverBridge.detachMediaSessionToken()
            mediaSessionAdapter.release()
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
        activePlayer?.release()
        activePlayer = null
        activeQueueAppender = null
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
                .setAspectRatio(activePictureInPictureAspectRatio())
                .apply { videoBounds?.let(::setSourceRectHint) }
                .build(),
        )
    }

    private fun stopPlaybackAndFinish() {
        if (stopRequested) return
        stopRequested = true
        activePlayer?.release()
        activePlayer = null
        activeQueueAppender = null
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
        val positionMs = (activePlayer?.currentPositionMs() ?: activeState.positionMs).coerceAtLeast(0L)
        val requestedSessionId = EmbyStream.newPlaySessionId()

        embyRepository
            .playbackInfo(
                server = server,
                itemId = item.id,
                mediaSourceId = sourceId,
                startPositionTicks = positionMs.toEmbyTicks(),
                playSessionId = requestedSessionId,
                sourceRequiresDolbyDecoder = item.activeVersion?.needsDolbyDecoder == true,
            ).onSuccess { playbackInfo ->
                if (
                    activeState.currentIndex != index ||
                    playbackItems.value.getOrNull(index)?.id != item.id
                ) {
                    return@onSuccess
                }
                val mediaVersions =
                    playbackInfo.MediaSources.mapIndexed { ordinal, source ->
                        source.toMediaVersion(fallbackId = item.id, ordinal = ordinal)
                    }
                val preferredVersionId =
                    mediaVersions
                        .preferredVersion(
                            playbackPreferences.mediaVersionPreference.value,
                            explicitVersionId = sourceId,
                        )?.id
                val versions =
                    mediaVersions.toPlayerMediaVersions(
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
                    versions.firstOrNull { version -> version.id == preferredVersionId }
                        ?: versions.firstOrNull()
                        ?: return@onSuccess
                val refreshed =
                    item
                        .copy(
                            url = selected.url,
                            transcodeUrl = selected.transcodeUrl,
                            fallbackTranscodeUrl = selected.fallbackTranscodeUrl,
                            versions = versions,
                            versionId = selected.id,
                            playSessionId = selected.playSessionId,
                            playMethod = selected.playMethod,
                            forcedTranscodeReason = null,
                            trickplay = item.trickplay.takeIf { selected.id == sourceId },
                        ).let { updated ->
                            if (televisionDevice) updated.withoutServerTranscodeForTv() else updated
                        }
                val currentQueue = playbackItems.value
                val refreshedQueue =
                    currentQueue.toMutableList().apply { set(index, refreshed) }
                val playbackSourcesChanged = !currentQueue.hasSamePlaybackSourcesAs(refreshedQueue)
                playbackItems.value = refreshedQueue
                if (playbackSourcesChanged) {
                    queueResume.value = index to positionMs
                    queueRevision.value++
                }
                AppLog.info(
                    category = "player.capabilities",
                    event = "server_playback_renegotiated",
                    message = "PlaybackInfo was refreshed after the output route changed",
                    attributes =
                        mapOf(
                            "revision" to capabilityRevision.toString(),
                            "itemIndex" to index.toString(),
                            "playMethod" to refreshed.playMethod.name,
                            "mediaSourceId" to refreshed.versionId.orEmpty(),
                            "engineRestarted" to playbackSourcesChanged.toString(),
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
    private fun refreshEpisodes(force: Boolean = false) {
        if (episodeRefreshJob?.isActive == true) return
        val snapshot = playbackItems.value
        val seed = snapshot.firstOrNull { it.seriesId != null && it.serverId != null } ?: return
        val seriesId = seed.seriesId ?: return
        val server = seed.serverId?.let(serverRegistry::serverById) ?: return
        val now = SystemClock.elapsedRealtime()
        if (
            !force &&
            lastEpisodeRefreshElapsedMs > 0L &&
            now - lastEpisodeRefreshElapsedMs < EPISODE_REFRESH_COOLDOWN_MS
        ) {
            return
        }
        lastEpisodeRefreshElapsedMs = now

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
                val refreshedFromServer =
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
                            seriesId = seriesId,
                            seriesName = seed.seriesName,
                            seriesKey =
                                skipSeriesStorageKey(
                                    serverId = server.id,
                                    seriesId = seriesId,
                                    providerSeriesKey = seriesProviderIds.watchKey(seriesId),
                                ),
                            stillUrl = stillUrl,
                            posterUrl = seriesPosterUrl,
                            progress = progress,
                            caption = episode.indexNumber?.let { "第 $it 集" },
                        ) ?: run {
                            val selectedVersionId =
                                episode.versions
                                    .preferredVersion(playbackPreferences.mediaVersionPreference.value)
                                    ?.id
                            val versions =
                                episode.versions.toPlayerMediaVersions(
                                    baseUrl = server.baseUrl,
                                    itemId = episode.id,
                                    token = server.accessToken,
                                )
                            val selected =
                                versions.firstOrNull { it.id == selectedVersionId }
                                    ?: versions.firstOrNull()
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
                                seriesKey =
                                    skipSeriesStorageKey(
                                        serverId = server.id,
                                        seriesId = seriesId,
                                        providerSeriesKey = seriesProviderIds.watchKey(seriesId),
                                    ),
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
                val refreshed =
                    if (televisionDevice) {
                        refreshedFromServer.withoutServerTranscodeForTv()
                    } else {
                        refreshedFromServer
                    }
                val current = playbackItems.value
                if (refreshed == current) return@launch

                // A show that published an episode while this one plays is the common case, and
                // the engine can take it as an extension of its playlist. Only a change it
                // cannot absorb that way is worth interrupting the viewer for.
                val appended = current.appendedBy(refreshed)
                val absorbedByPlayer =
                    appended != null && activeQueueAppender?.invoke(appended) == true
                val absorbed = appended != null && absorbedByPlayer
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
                        (activePlayer?.currentPositionMs() ?: activeState.positionMs)
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
        mediaSessionAdapter =
            TvMediaSessionAdapter(
                context = this,
                tag = "YfusePlayer",
                actions =
                    object : TvMediaSessionActions {
                        override fun play() = requestPlaybackStart()

                        override fun pause() = requestPlaybackPause()

                        override fun seekTo(positionMs: Long) = seekPlaybackTo(positionMs)

                        override fun previous() = selectAdjacentPlayback(-1)

                        override fun next() = selectAdjacentPlayback(1)
                    },
            )
        if (televisionDevice) {
            CastConnectReceiverBridge.attachMediaSessionToken(mediaSessionAdapter.compatToken)
        }
    }

    private fun registerScreenStateReceiver() {
        val filter =
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            }
        ContextCompat.registerReceiver(
            this,
            screenStateReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        screenStateReceiverRegistered = true
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
                .setAspectRatio(activePictureInPictureAspectRatio())
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
            activePlayer?.pause()
        } else if (state.ended || state.error != null) {
            abandonAudioFocus()
        }
        mediaSessionAdapter.update(
            TvMediaSessionState(
                playing = state.playing,
                buffering = state.buffering,
                ended = state.ended,
                positionMs = state.positionMs,
                durationMs = state.durationMs,
                speed = state.speed,
                title = sessionTitles.getOrNull(state.currentIndex).orEmpty(),
                hasPrevious = state.hasPrevious,
                hasNext = state.hasNext,
                error = state.error,
            ),
        )
        notificationController.update(state, sessionTitles)
    }

    private fun requestPlaybackStart() {
        if (!playbackAllowedByLifecycle()) return
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

    private fun requestPlaybackPause() {
        val castManager = remoteCastManager
        if (castManager?.state?.value?.hasActiveSession == true) {
            lifecycleScope.launch { castManager.pause() }
        } else {
            playbackGate?.pause()
        }
    }

    private fun seekPlaybackTo(positionMs: Long) {
        val durationMs = activeState.durationMs
        val target =
            if (durationMs > 0L) {
                positionMs.coerceIn(0L, durationMs)
            } else {
                positionMs.coerceAtLeast(0L)
            }
        val castManager = remoteCastManager
        if (castManager?.state?.value?.hasActiveSession == true) {
            lifecycleScope.launch { castManager.seekTo(target) }
        } else {
            playbackGate?.seekTo(target)
        }
    }

    private fun togglePlaybackWithFocus() {
        if (!activeState.playing && !playbackAllowedByLifecycle()) return
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
        val fallbackUrl = item.transcodeUrl.ifBlank { item.fallbackTranscodeUrl }
        lifecycleScope.launch {
            val queued =
                if (offset < 0) {
                    castManager.queuePrevious()
                } else {
                    castManager.queueNext()
                }
            val loaded =
                queued ||
                    castManager.play(
                        deviceId = deviceId,
                        mediaUrl = item.url,
                        title = item.title,
                        positionMs = 0L,
                        fallbackMediaUrl = fallbackUrl,
                        mediaProfile = item.castMediaProfile(),
                    )
            if (loaded) {
                activePlayer?.selectItem(targetIndex)
                activePlayer?.pause()
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

    private fun playbackAllowedByLifecycle(): Boolean =
        isScreenInteractive() &&
            (
                !activityHasStarted ||
                    activityStarted ||
                    isInPictureInPictureMode
            )

    private fun activePictureInPictureAspectRatio(): Rational {
        val width =
            activeState.diagnostics.videoWidth.takeIf { it > 0 }
                ?: videoBounds?.width()
                ?: 16
        val height =
            activeState.videoHeight.takeIf { it > 0 }
                ?: videoBounds?.height()
                ?: 9
        val (ratioWidth, ratioHeight) = pictureInPictureAspectRatioDimensions(width, height)
        return Rational(ratioWidth, ratioHeight)
    }

    private fun isScreenInteractive(): Boolean = getSystemService(PowerManager::class.java).isInteractive

    private fun pausePlaybackForNoisyOutput() {
        if (remoteCastManager?.state?.value?.hasActiveSession == true) return
        activePlayer?.pause()
        abandonAudioFocus()
        AppLog.info(
            category = "player.audio",
            event = "becoming_noisy",
            message = "Playback paused before audio could move to the device speaker",
        )
    }

    private fun pausePlaybackForLifecycle(reason: String) {
        if (stopRequested || lifecyclePauseRequested) return
        val castManager = remoteCastManager
        val remoteActive = castManager?.state?.value?.hasActiveSession == true
        val playbackActive = activeState.playing || activeState.buffering || remoteActive
        if (!playbackActive) return

        lifecyclePauseRequested = true
        if (remoteActive && castManager != null) {
            lifecycleScope.launch { castManager.pause() }
        } else {
            // Lifecycle safety must not be rejected by watch-together guest controls.
            activePlayer?.pause()
            abandonAudioFocus()
        }
        AppLog.info(
            category = "feature.player",
            event = "playback_paused_for_lifecycle",
            message = "Playback paused because the player is no longer safely visible",
            attributes =
                mapOf(
                    "reason" to reason,
                    "pictureInPicture" to isInPictureInPictureMode.toString(),
                    "screenInteractive" to isScreenInteractive().toString(),
                    "remoteCast" to remoteActive.toString(),
                ),
        )
    }

    private fun ensureAudioFocus(): Boolean = audioFocusController.ensure()

    private fun abandonAudioFocus() {
        if (::audioFocusController.isInitialized) audioFocusController.abandon()
    }
}

internal fun pictureInPictureAspectRatioDimensions(
    width: Int,
    height: Int,
): Pair<Int, Int> {
    if (width <= 0 || height <= 0) return 16 to 9
    val ratio = width.toDouble() / height.toDouble()
    return when {
        ratio < MIN_PICTURE_IN_PICTURE_ASPECT_RATIO -> 100 to 239
        ratio > MAX_PICTURE_IN_PICTURE_ASPECT_RATIO -> 239 to 100
        else -> {
            var left = width
            var right = height
            while (right != 0) {
                val remainder = left % right
                left = right
                right = remainder
            }
            width / left to height / left
        }
    }
}

private fun Long.toEmbyTicks(): Long =
    coerceIn(0L, Long.MAX_VALUE / EMBY_TICKS_PER_MILLISECOND) * EMBY_TICKS_PER_MILLISECOND

private const val EMBY_TICKS_PER_MILLISECOND = 10_000L
private const val MIN_PICTURE_IN_PICTURE_ASPECT_RATIO = 1.0 / 2.39
private const val MAX_PICTURE_IN_PICTURE_ASPECT_RATIO = 2.39
