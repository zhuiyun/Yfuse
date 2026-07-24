package com.yfuse.feature.player

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.data.ThemePreferences
import com.yfuse.core.designsystem.AccentColor
import com.yfuse.core.designsystem.YfuseTheme
import com.yfuse.core.model.DecoderMode
import com.yfuse.core.model.PlaybackQuality
import com.yfuse.core.model.PlayerEngine
import com.yfuse.core.network.EmbyStream
import kotlinx.coroutines.flow.MutableStateFlow
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
            putExtra(EXTRA_INDEX, startIndex)
            putExtra(EXTRA_POSITION, startPositionMs)
            putExtra(EXTRA_ENGINE, engine.name)
            putExtra(EXTRA_DECODER, decoder.name)
            putExtra(EXTRA_AUTO_NEXT, autoNext)
            putExtra(EXTRA_QUALITY, quality.name)
        }
    }

    private var activeEngine: VideoEngine? = null
    private var activeState = PlaybackState()
    private var sessionTitles: List<String> = emptyList()
    private val pictureInPicture = MutableStateFlow(false)
    private lateinit var mediaSession: MediaSession
    private lateinit var notificationManager: NotificationManager
    private var mediaReceiverRegistered = false
    private var videoBounds: Rect? = null
    private val mediaActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_PREVIOUS -> {
                    val previous = activeState.currentIndex - 1
                    if (previous >= 0) activeEngine?.selectItem(previous)
                }
                ACTION_PLAY_PAUSE -> {
                    if (activeState.playing) activeEngine?.pause() else activeEngine?.play()
                }
                ACTION_NEXT -> {
                    val next = activeState.currentIndex + 1
                    if (next < activeState.itemCount) activeEngine?.selectItem(next)
                }
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
            )
        }
        sessionTitles = items.map { it.title }

        createMediaSession()
        createPlaybackNotificationChannel()
        registerMediaActionReceiver()
        requestNotificationPermissionIfNeeded()

        val koin = GlobalContext.get()
        val preferences = runCatching { koin.get<ThemePreferences>() }.getOrNull()
        val accent = preferences?.accent?.value ?: AccentColor.Blue
        val playbackSink = runCatching {
            val server = koin.get<ServerRegistry>().defaultServer
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
                    onEngineAttached = { engine -> activeEngine = engine },
                    onEngineDetached = { engine ->
                        if (activeEngine === engine) activeEngine = null
                    },
                    onPlaybackState = { state ->
                        activeState = state
                        updateMediaSession(state)
                        updatePictureInPictureParams()
                    },
                    onVideoBounds = { bounds ->
                        videoBounds = bounds
                        updatePictureInPictureParams()
                    },
                    onBack = { finish() },
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
    }

    override fun onStop() {
        super.onStop()
        if (!isInPictureInPictureMode && !isChangingConfigurations) {
            activeEngine?.pause()
        }
    }

    override fun onDestroy() {
        notificationManager.cancel(NOTIFICATION_ID)
        if (mediaReceiverRegistered) {
            runCatching { unregisterReceiver(mediaActionReceiver) }
            mediaReceiverRegistered = false
        }
        mediaSession.isActive = false
        mediaSession.release()
        super.onDestroy()
    }

    private fun createMediaSession() {
        mediaSession = MediaSession(this, "YfusePlayer").apply {
            setCallback(
                object : MediaSession.Callback() {
                    override fun onPlay() {
                        activeEngine?.play()
                    }

                    override fun onPause() {
                        activeEngine?.pause()
                    }

                    override fun onSeekTo(pos: Long) {
                        activeEngine?.seekTo(pos)
                    }

                    override fun onSkipToNext() {
                        val next = activeState.currentIndex + 1
                        if (next < activeState.itemCount) activeEngine?.selectItem(next)
                    }

                    override fun onSkipToPrevious() {
                        val previous = activeState.currentIndex - 1
                        if (previous >= 0) activeEngine?.selectItem(previous)
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
    onEngineAttached: (VideoEngine) -> Unit,
    onEngineDetached: (VideoEngine) -> Unit,
    onPlaybackState: (PlaybackState) -> Unit,
    onVideoBounds: (Rect) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var kind by remember { mutableStateOf(initialEngine) }
    // Where a newly built engine should start: index + position, updated on
    // every handover so the switch is seamless.
    var resume by remember { mutableStateOf(startIndex to startPositionMs) }
    var filled by remember { mutableStateOf(false) }

    val engine: VideoEngine = remember(kind, resume) {
        when (kind) {
            PlayerEngine.Mpv -> MpvVideoEngine(
                context = context,
                items = items,
                startIndex = resume.first,
                startPositionMs = resume.second,
                decoderMode = decoderMode,
                autoNext = autoNext,
                quality = quality,
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
    val latestState by rememberUpdatedState(state)
    val reporter = remember(items, playbackSink) {
        playbackSink?.let { PlaybackProgressReporter(items, it) }
    }
    LaunchedEffect(state, reporter) {
        reporter?.update(state)
        onPlaybackState(state)
    }
    DisposableEffect(reporter) {
        onDispose { reporter?.close(latestState) }
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
            is MpvVideoEngine -> MpvSurface(engine, Modifier.fillMaxSize())
            is ExoVideoEngine -> ExoSurface(engine, filled, Modifier.fillMaxSize())
        }

        if (!inPictureInPicture) {
            PlayerControls(
                state = state,
                titles = items.map { it.title },
                filled = filled,
                onBack = onBack,
                onPlayPause = { if (state.playing) engine.pause() else engine.play() },
                onRetry = engine::retry,
                onSeek = engine::seekTo,
                onSelectItem = engine::selectItem,
                onSelectAudio = engine::selectAudioTrack,
                onSelectSubtitle = engine::selectSubtitleTrack,
                onSpeed = engine::setSpeed,
                onToggleFill = {
                    filled = !filled
                    (engine as? MpvVideoEngine)?.setFill(filled)
                },
                volume = volume,
                onVolume = { setVolume(it) },
                engineOptions = PlayerEngine.selectable.map { it.label to (it == kind) },
                onSelectEngine = { index -> switchEngine(PlayerEngine.selectable[index]) },
                // Manual escape hatch when the picture is black but audio plays.
                transcodeLabel = if (exo != null) "转码播放" else null,
                transcodeActive = transcoding,
                onTranscode = { exo?.switchToTranscode() },
            )
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
