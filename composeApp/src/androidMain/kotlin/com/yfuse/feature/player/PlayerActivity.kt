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
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState as PlatformPlaybackState
import android.os.Build
import android.os.Bundle
import android.util.Rational
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
import com.yfuse.core.data.DanmakuComment
import com.yfuse.core.data.DanmakuDisplayArea
import com.yfuse.core.data.DanmakuFontSize
import com.yfuse.core.data.DanmakuMedia
import com.yfuse.core.data.DanmakuOpacity
import com.yfuse.core.data.DanmakuPreferences
import com.yfuse.core.data.DanmakuRepository
import com.yfuse.core.data.DanmakuSpeed
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.PlaybackRecoveryStore
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.data.ThemePreferences
import com.yfuse.core.data.UserAgentPreferences
import com.yfuse.core.data.WatchTogetherPreferences
import com.yfuse.core.cast.CastManager
import com.yfuse.core.designsystem.AccentColor
import com.yfuse.core.designsystem.YfuseTheme
import com.yfuse.core.model.DecoderMode
import com.yfuse.core.model.PlaybackQuality
import com.yfuse.core.model.PlaybackSegment
import com.yfuse.core.model.PlaybackSegmentType
import com.yfuse.core.model.PlayerEngine
import com.yfuse.core.network.EmbyStream
import com.yfuse.core.sync.WatchTogetherClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext
import kotlin.math.roundToInt

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
        private const val EXTRA_SEASONS = "yfuse.seasonNumbers"
        private const val EXTRA_EPISODES = "yfuse.episodeNumbers"
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
            putExtra(EXTRA_SEASONS, items.map { it.seasonNumber ?: -1 }.toIntArray())
            putExtra(EXTRA_EPISODES, items.map { it.episodeNumber ?: -1 }.toIntArray())
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
    private var sessionTitles: List<String> = emptyList()
    private val pictureInPicture = MutableStateFlow(false)
    private lateinit var mediaSession: MediaSession
    private lateinit var notificationManager: NotificationManager
    private var mediaReceiverRegistered = false
    private var videoBounds: Rect? = null
    private var pipWasVisible = false
    private var stopRequested = false
    private val mediaActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_PREVIOUS -> playbackGate?.selectPrevious()
                ACTION_PLAY_PAUSE -> playbackGate?.togglePlayPause()
                ACTION_NEXT -> playbackGate?.selectNext()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
        val seasonNumbers = intent.getIntArrayExtra(EXTRA_SEASONS) ?: intArrayOf()
        val episodeNumbers = intent.getIntArrayExtra(EXTRA_EPISODES) ?: intArrayOf()
        val items = urls.mapIndexed { index, url ->
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
                watchKey = watchKeys.getOrElse(index) {
                    "emby:${ids.getOrElse(index) { index.toString() }}"
                },
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
        val preferences = runCatching { koin.get<ThemePreferences>() }.getOrNull()
        val danmakuPreferences = koin.get<DanmakuPreferences>()
        val danmakuRepository = koin.get<DanmakuRepository>()
        val playbackRecovery = koin.get<PlaybackRecoveryStore>()
        val customUserAgent = koin.get<UserAgentPreferences>().userAgent.value
        val watchTogether = koin.get<WatchTogetherClient>()
        val watchTogetherPreferences = koin.get<WatchTogetherPreferences>()
        playbackGate = WatchGatedPlayback(
            watchTogether = watchTogether,
            items = { items },
            engine = { activeEngine },
            onLocked = {
                runOnUiThread {
                    Toast.makeText(this, "当前由房主控制播放", Toast.LENGTH_SHORT).show()
                }
            },
        )
        ActivePlayback.bind(
            toggle = { playbackGate?.togglePlayPause() },
            open = {
                startActivity(
                    Intent(this, PlayerActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                )
            },
            close = ::stopPlaybackAndFinish,
        )
        val accent = preferences?.accent?.value ?: AccentColor.Blue
        val playbackSink = runCatching {
            val registry = koin.get<ServerRegistry>()
            val selectedServerId = items.getOrNull(
                intent.getIntExtra(EXTRA_INDEX, 0),
            )?.serverId
            val server = selectedServerId?.let(registry::serverById) ?: registry.defaultServer
            val repo = koin.get<EmbyRepository>()
            server?.let { EmbyPlaybackEventSink(repo, it) }
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
                    danmakuRepository = danmakuRepository,
                    playbackRecovery = playbackRecovery,
                    customUserAgent = customUserAgent,
                    watchTogether = watchTogether,
                    watchTogetherPreferences = watchTogetherPreferences,
                    playbackGate = requireNotNull(playbackGate),
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
        notificationManager.cancel(NOTIFICATION_ID)
        if (mediaReceiverRegistered) {
            runCatching { unregisterReceiver(mediaActionReceiver) }
            mediaReceiverRegistered = false
        }
        mediaSession.isActive = false
        mediaSession.release()
        super.onDestroy()
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
        playbackGate = null
        ActivePlayback.clear()
        stopService(Intent(this, PlaybackKeepAliveService::class.java))
        finishAndRemoveTask()
    }

    private fun createMediaSession() {
        mediaSession = MediaSession(this, "YfusePlayer").apply {
            setCallback(
                object : MediaSession.Callback() {
                    override fun onPlay() {
                        playbackGate?.play()
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

/** Speed offset used to close a nudge-range gap without an audible/visible jump. */
private const val NUDGE_FRACTION = 0.02f

/** Avoids reissuing `setSpeed` every tick for a rate that hasn't materially changed. */
private const val RATE_EPSILON = 0.001f

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

    var kind by remember { mutableStateOf(initialEngine) }
    // Where a newly built engine should start: index + position, updated on
    // every handover so the switch is seamless.
    var resume by remember { mutableStateOf(startIndex to startPositionMs) }
    var filled by remember { mutableStateOf(false) }

    val engine: VideoEngine = remember(kind, resume) {
        when (kind) {
            PlayerEngine.Mdk -> MdkVideoEngine(
                items = items,
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
                items = items,
                startIndex = resume.first,
                startPositionMs = resume.second,
                decoderMode = decoderMode,
                autoNext = autoNext,
                quality = quality,
                customUserAgent = customUserAgent,
            )
            else -> ExoVideoEngine(
                context = context,
                items = items,
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

    DisposableEffect(engine) {
        onEngineAttached(engine)
        onDispose {
            onEngineDetached(engine)
            engine.release()
        }
    }

    val state by engine.state.collectAsState()
    val watchState by watchTogether.state.collectAsState()
    val watchEndpoint by watchTogetherPreferences.endpoint.collectAsState()
    val danmakuUrl by danmakuPreferences.urlTemplate.collectAsState()
    val danmakuEnabled by danmakuPreferences.enabled.collectAsState()
    val danmakuArea by danmakuPreferences.displayArea.collectAsState()
    val danmakuFont by danmakuPreferences.fontSize.collectAsState()
    val danmakuSpeed by danmakuPreferences.speed.collectAsState()
    val danmakuOpacity by danmakuPreferences.opacity.collectAsState()
    var danmakuComments by remember { mutableStateOf(emptyList<DanmakuComment>()) }
    var danmakuLoading by remember { mutableStateOf(false) }
    var danmakuError by remember { mutableStateOf<String?>(null) }
    val currentItem = items.getOrNull(state.currentIndex)
    val activeSegment = currentItem?.playbackSegments?.firstOrNull { segment ->
        segment.contains(state.positionMs, state.durationMs)
    }
    LaunchedEffect(currentItem?.id, danmakuUrl, danmakuEnabled) {
        danmakuComments = emptyList()
        danmakuError = null
        danmakuLoading = false
        val item = currentItem ?: return@LaunchedEffect
        if (!danmakuEnabled || danmakuUrl.isBlank()) return@LaunchedEffect

        danmakuLoading = true
        danmakuRepository.load(
            template = danmakuUrl,
            media = DanmakuMedia(
                id = item.id,
                title = item.title,
                season = item.seasonNumber,
                episode = item.episodeNumber,
                serverId = item.serverId,
            ),
        ).fold(
            onSuccess = { danmakuComments = it },
            onFailure = { danmakuError = it.message ?: "弹幕加载失败" },
        )
        danmakuLoading = false
    }
    val latestEngine by rememberUpdatedState(engine)
    val latestPlaybackState by rememberUpdatedState(state)
    val mediaMatcher = remember(watchTogether) {
        WatchMediaMatcher(watchTogether::setSyncWarning)
    }

    // Guest side: the room's timeline is server-authoritative and near-silent between
    // events (see WatchTogetherClient), so following it needs a tick of our own rather
    // than only reacting to messages. Position drift is corrected in three tiers instead
    // of always hard-seeking: under NUDGE_THRESHOLD_MS is imperceptible and left alone;
    // under HARD_SEEK_THRESHOLD_MS is closed by nudging playback speed ±2% so the catch-up
    // is invisible; only a gap that large — a fresh join, a long stall — jumps outright.
    // The rate this computes is also enforced every tick regardless of drift, which is
    // what keeps 倍速 shared: a guest's own speed change (menu or the long-press gesture)
    // gets quietly overwritten back to the room's rate within one tick instead of needing
    // a separate lock on that control.
    LaunchedEffect(watchState.connected, watchState.reconnecting, watchState.isHost) {
        if (!watchState.connected || watchState.isHost) {
            mediaMatcher.reset()
            return@LaunchedEffect
        }
        var lastAppliedRate: Float? = null
        var lastNominalRate: Float? = null
        try {
            while (isActive) {
                val timeline = watchTogether.timeline.value
                if (timeline != null) {
                    lastNominalRate = timeline.rate
                    val targetIndex = mediaMatcher.resolve(items, timeline.mediaKey)
                    if (targetIndex != null) {
                        if (targetIndex != latestPlaybackState.currentIndex) {
                            latestEngine.selectItem(targetIndex)
                        }
                        val expected = timeline.expectedPositionMs(watchTogether.estimatedServerNow())
                        val diff = expected - latestEngine.currentPositionMs()
                        val desiredRate = when {
                            kotlin.math.abs(diff) >= HARD_SEEK_THRESHOLD_MS -> {
                                latestEngine.seekTo(expected)
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
        val item = items.getOrNull(state.currentIndex)
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

    fun switchEngine(target: PlayerEngine) {
        if (target == kind) return
        // Read the position before the old engine is torn down.
        engine.pause()
        resume = state.currentIndex to engine.currentPositionMs()
        kind = target
    }

    val exo = engine as? ExoVideoEngine
    val idleTranscoding = remember { MutableStateFlow(false) }
    val transcoding by (exo?.transcoding ?: idleTranscoding).collectAsState()
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

        if (!inPictureInPicture && danmakuEnabled && danmakuComments.isNotEmpty()) {
            DanmakuOverlay(
                comments = danmakuComments,
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
                titles = items.map { it.title },
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
                brightness = brightness,
                onBrightness = { setBrightness(it) },
                engineOptions = PlayerEngine.selectable.map { it.label to (it == kind) },
                onSelectEngine = { index -> switchEngine(PlayerEngine.selectable[index]) },
                // Manual escape hatch when the picture is black but audio plays.
                transcodeLabel = if (exo != null) "转码播放" else null,
                transcodeActive = transcoding,
                onTranscode = { exo?.switchToTranscode() },
                castDevices = castState.devices.map { it.id to it.name },
                castingDeviceId = castState.activeDeviceId,
                castDiscovering = castState.discovering,
                castError = castState.error,
                onDiscoverCast = { scope.launch { castManager.discover() } },
                onCastTo = { deviceId ->
                    val item = items.getOrNull(state.currentIndex) ?: return@PlayerControls
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
                danmakuConfigured = danmakuUrl.isNotBlank(),
                danmakuEnabled = danmakuEnabled,
                danmakuCount = danmakuComments.size,
                danmakuLoading = danmakuLoading,
                danmakuError = danmakuError,
                danmakuAreaOptions = DanmakuDisplayArea.entries.map {
                    it.label to (it == danmakuArea)
                },
                danmakuFontOptions = DanmakuFontSize.entries.map {
                    it.label to (it == danmakuFont)
                },
                danmakuSpeedOptions = DanmakuSpeed.entries.map {
                    it.label to (it == danmakuSpeed)
                },
                danmakuOpacityOptions = DanmakuOpacity.entries.map {
                    it.label to (it == danmakuOpacity)
                },
                onToggleDanmaku = {
                    danmakuPreferences.setEnabled(!danmakuEnabled)
                },
                onSelectDanmakuArea = { index ->
                    danmakuPreferences.setDisplayArea(DanmakuDisplayArea.entries[index])
                },
                onSelectDanmakuFont = { index ->
                    danmakuPreferences.setFontSize(DanmakuFontSize.entries[index])
                },
                onSelectDanmakuSpeed = { index ->
                    danmakuPreferences.setSpeed(DanmakuSpeed.entries[index])
                },
                onSelectDanmakuOpacity = { index ->
                    danmakuPreferences.setOpacity(DanmakuOpacity.entries[index])
                },
                skipSegmentLabel = activeSegment?.type?.skipLabel,
                onSkipSegment = {
                    when (activeSegment?.type) {
                        PlaybackSegmentType.Intro -> {
                            activeSegment.endMs?.let(playbackGate::seekTo)
                        }
                        PlaybackSegmentType.Credits -> {
                            if (state.hasNext) {
                                playbackGate.selectNext()
                            } else {
                                playbackGate.seekTo(
                                    (state.durationMs - 500L).coerceAtLeast(0L),
                                )
                            }
                        }
                        null -> Unit
                    }
                },
                watchEndpoint = watchEndpoint,
                watchConnecting = watchState.connecting,
                watchConnected = watchState.connected,
                watchReconnecting = watchState.reconnecting,
                watchRoomCode = watchState.roomCode,
                watchIsHost = watchState.isHost,
                watchParticipantCount = watchState.participantCount,
                watchError = watchState.error ?: watchState.syncWarning,
                onCreateWatchRoom = { endpoint ->
                    currentItem?.let { item ->
                        watchTogether.createRoom(endpoint, item.watchKey)
                    }
                },
                onJoinWatchRoom = { endpoint, roomCode ->
                    currentItem?.let { item ->
                        watchTogether.joinRoom(endpoint, roomCode, item.watchKey)
                    }
                },
                onLeaveWatchRoom = watchTogether::leave,
            )
        }
    }
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
    var level by remember {
        mutableFloatStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max)
    }
    return level to { target: Float ->
        val clamped = target.coerceIn(0f, 1f)
        audio.setStreamVolume(
            AudioManager.STREAM_MUSIC,
            (clamped * max).toInt().coerceIn(0, max),
            0,
        )
        level = clamped
    }
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
