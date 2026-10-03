package com.yfuse.core.cast

import android.content.Context
import android.net.wifi.WifiManager
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.Cast
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaQueueItem
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.MediaTrack
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.api.PendingResult
import com.google.android.gms.common.api.Result
import com.yfuse.core.data.UserAgentPreferences
import com.yfuse.core.logging.AppLog
import com.yfuse.core.network.DEFAULT_EMBY_USER_AGENT
import com.yfuse.core.network.LocalNetworkPermissionRequiredException
import com.yfuse.core.network.requireLocalNetworkPermission
import com.yfuse.feature.player.PlaybackHttpDataSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.koin.core.context.GlobalContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import kotlin.coroutines.resume

private lateinit var castApplicationContext: Context

fun initializeCastApplicationContext(context: Context) {
    castApplicationContext = context.applicationContext
}

actual fun createCastManager(): CastManager = AndroidCastManager(castApplicationContext)

private data class DlnaTarget(
    val public: CastDevice,
    val avTransportUrl: String,
    val renderingControlUrl: String?,
)

private data class DlnaSnapshot(
    val status: CastPlaybackStatus,
    val positionMs: Long?,
    val durationMs: Long?,
    /** The renderer's CurrentTransportState, for diagnostics. */
    val transportState: String,
)

/** The address a renderer is given: this phone's relay, or the media server itself. */
private class DlnaRoute(
    val url: String,
    val relay: DlnaMediaRelay?,
    /** Why the media goes through the relay; also kept when the relay could not be opened. */
    val reason: String?,
    val renderer: InetAddress?,
)

/** What a relay serves for the active session, to put it back after a failed replacement load. */
private class DlnaRelayMedia(
    val upstreamUrl: String,
    val format: DlnaMediaFormat,
    val renderer: InetAddress,
)

private class DlnaLoad(
    val snapshot: DlnaSnapshot,
    val seekCapability: CastCapability,
    val seekedBeforePlay: Boolean,
)

/** The start phase of one DLNA load; see [DlnaStartMonitor]. */
private class DlnaStart(
    val targetPositionMs: Long,
    val monitor: DlnaStartMonitor,
    val startedAtMs: Long,
    var seekCapability: CastCapability,
    var seekAttempts: Int,
)

private enum class ActiveProtocol { Chromecast, Dlna }

private class CastHttpException(
    val statusCode: Int,
) : IllegalStateException("HTTP $statusCode")

private class AndroidCastManager(
    private val context: Context,
) : CastManager {
    private val mutableState = MutableStateFlow(CastState())
    override val state = mutableState.asStateFlow()
    private val targets = linkedMapOf<String, DlnaTarget>()
    private val castRoutes = linkedMapOf<String, MediaRouter.RouteInfo>()

    // Session ownership and receiver callbacks are serialized on Main; network work runs on IO.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var dlnaPollJob: Job? = null
    private var activeProtocol: ActiveProtocol? = null
    private var castClient: RemoteMediaClient? = null
    private var castMessageSession: CastSession? = null
    private var sessionListenerRegistered = false
    private var suppressNextSessionEnd = false
    private var activeDiscovery: Any? = null

    /** Serves the active DLNA session's media when the renderer reads it through this phone. */
    private var dlnaRelay: DlnaMediaRelay? = null
    private var dlnaRelayMedia: DlnaRelayMedia? = null

    /** Set from an accepted DLNA load until its renderer shows it is playing, or fails to. */
    private var dlnaStart: DlnaStart? = null

    /** False while a started renderer's clock has not been seen to move; its position is then unused. */
    private var dlnaClockTrusted = true
    private var dlnaClockWatch: DlnaClockWatch? = null

    /** When the renderer last went from playing to STOPPED, while a relay still served it. */
    private var dlnaEndedSinceMs: Long? = null
    private val userAgentPreferences by lazy {
        runCatching { GlobalContext.get().get<UserAgentPreferences>() }.getOrNull()
    }

    private val castMessageCallback =
        Cast.MessageReceivedCallback { _, namespace, message ->
            if (namespace == CAST_OUTPUT_NAMESPACE) handleCastReceiverMessage(message)
        }

    private val mediaRouter by lazy { MediaRouter.getInstance(context) }
    private val castSelector =
        MediaRouteSelector
            .Builder()
            .addControlCategory(
                CastMediaControlIntent.categoryForCast(
                    configuredCastReceiverApplicationId(),
                ),
            ).build()

    private val routeCallback =
        object : MediaRouter.Callback() {
            override fun onRouteAdded(
                router: MediaRouter,
                route: MediaRouter.RouteInfo,
            ) = refreshCastRoutes()

            override fun onRouteChanged(
                router: MediaRouter,
                route: MediaRouter.RouteInfo,
            ) = refreshCastRoutes()

            override fun onRouteRemoved(
                router: MediaRouter,
                route: MediaRouter.RouteInfo,
            ) {
                val removedId = CAST_PREFIX + route.id
                refreshCastRoutes()
                if (
                    activeProtocol == ActiveProtocol.Chromecast &&
                    mutableState.value.activeDeviceId == removedId &&
                    !suppressNextSessionEnd
                ) {
                    markUnexpectedDisconnect("Chromecast 连接已断开")
                }
            }

            override fun onRouteUnselected(
                router: MediaRouter,
                route: MediaRouter.RouteInfo,
                reason: Int,
            ) {
                if (
                    activeProtocol == ActiveProtocol.Chromecast &&
                    mutableState.value.activeDeviceId == CAST_PREFIX + route.id &&
                    !suppressNextSessionEnd
                ) {
                    markUnexpectedDisconnect("Chromecast 会话已结束")
                }
            }
        }

    private val castProgressListener =
        RemoteMediaClient.ProgressListener { positionMs, durationMs ->
            if (
                activeProtocol != ActiveProtocol.Chromecast ||
                mutableState.value.activeDeviceId?.startsWith(CAST_PREFIX) != true
            ) {
                return@ProgressListener
            }
            mutableState.update {
                it.remoteUpdate(
                    status = it.status,
                    positionMs = positionMs,
                    durationMs = durationMs,
                )
            }
        }

    private val castCallback =
        object : RemoteMediaClient.Callback() {
            override fun onStatusUpdated() = syncChromecastStatus()

            override fun onMediaError(error: com.google.android.gms.cast.MediaError) {
                mutableState.update {
                    it.commandFailed("Chromecast 播放错误：${error.reason ?: error.type}")
                }
            }
        }

    private val sessionListener =
        object : SessionManagerListener<CastSession> {
            override fun onSessionStarting(session: CastSession) = Unit

            override fun onSessionStarted(
                session: CastSession,
                sessionId: String,
            ) = attachCastClient(session)

            override fun onSessionStartFailed(
                session: CastSession,
                error: Int,
            ) {
                if (activeProtocol == ActiveProtocol.Chromecast) {
                    mutableState.update { it.commandFailed("Chromecast 会话建立失败（$error）") }
                }
            }

            override fun onSessionEnding(session: CastSession) = Unit

            override fun onSessionEnded(
                session: CastSession,
                error: Int,
            ) {
                detachCastClient()
                if (suppressNextSessionEnd) {
                    suppressNextSessionEnd = false
                    return
                }
                if (activeProtocol == ActiveProtocol.Chromecast) {
                    markUnexpectedDisconnect("Chromecast 会话意外结束（$error）")
                }
            }

            override fun onSessionResuming(
                session: CastSession,
                sessionId: String,
            ) = Unit

            override fun onSessionResumed(
                session: CastSession,
                wasSuspended: Boolean,
            ) = attachCastClient(session)

            override fun onSessionResumeFailed(
                session: CastSession,
                error: Int,
            ) {
                if (activeProtocol == ActiveProtocol.Chromecast) {
                    markUnexpectedDisconnect("Chromecast 会话恢复失败（$error）")
                }
            }

            override fun onSessionSuspended(
                session: CastSession,
                reason: Int,
            ) {
                if (activeProtocol == ActiveProtocol.Chromecast && !suppressNextSessionEnd) {
                    markUnexpectedDisconnect("Chromecast 连接已中断（$reason）")
                }
            }
        }

    override suspend fun discover() =
        withContext(Dispatchers.Main.immediate) {
            val discovery = Any()
            activeDiscovery = discovery
            mutableState.update { it.copy(discovering = true, error = null) }
            try {
                var discoveryError: Exception? = null
                val discoveredTargets =
                    try {
                        discoverDlnaTargets()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        discoveryError = error
                        AppLog.warning("cast", "discovery_failed", "DLNA discovery failed", error)
                        null
                    }
                if (activeDiscovery !== discovery) return@withContext
                discoveredTargets?.let {
                    targets.clear()
                    targets.putAll(it)
                }
                ensureCastCallbacks()
                mediaRouter.removeCallback(routeCallback)
                mediaRouter.addCallback(
                    castSelector,
                    routeCallback,
                    MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY,
                )
                refreshCastRoutes()
                val devices = allDevices()
                mutableState.update { current ->
                    current.copy(
                        devices = devices,
                        error =
                            when {
                                current.hasActiveSession -> current.error
                                discoveryError is LocalNetworkPermissionRequiredException -> discoveryError.message
                                devices.isEmpty() && discoveryError != null -> "投屏设备发现失败"
                                devices.isEmpty() -> "未发现可用的投屏设备"
                                else -> null
                            },
                    )
                }
            } finally {
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    // An older cancelled scan must not clear a replacement scan's spinner or data.
                    if (activeDiscovery === discovery) {
                        activeDiscovery = null
                        mutableState.update { it.copy(discovering = false) }
                    }
                }
            }
        }

    private suspend fun discoverDlnaTargets(): Map<String, DlnaTarget> =
        withContext(Dispatchers.IO) {
            val locations = linkedSetOf<String>()
            requireLocalNetworkPermission()
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val multicastLock =
                wifi?.createMulticastLock("yfuse-dlna")?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
            try {
                DatagramSocket().use { socket ->
                    socket.soTimeout = 350
                    val request =
                        (
                            "M-SEARCH * HTTP/1.1\r\n" +
                                "HOST: 239.255.255.250:1900\r\n" +
                                "MAN: \"ssdp:discover\"\r\n" +
                                "MX: 2\r\n" +
                                "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"
                        ).encodeToByteArray()
                    socket.send(
                        DatagramPacket(
                            request,
                            request.size,
                            InetAddress.getByName("239.255.255.250"),
                            1900,
                        ),
                    )
                    val deadline = System.currentTimeMillis() + 2_500L
                    while (System.currentTimeMillis() < deadline) {
                        currentCoroutineContext().ensureActive()
                        val data = ByteArray(8 * 1024)
                        val packet = DatagramPacket(data, data.size)
                        try {
                            socket.receive(packet)
                        } catch (_: SocketTimeoutException) {
                            continue
                        }
                        val response = data.decodeToString(0, packet.length)
                        Regex("""(?im)^location:\s*(.+)\s*$""")
                            .find(response)
                            ?.groupValues
                            ?.get(1)
                            ?.trim()
                            ?.let(locations::add)
                    }
                }
            } finally {
                multicastLock?.takeIf { it.isHeld }?.release()
            }

            val discoveredTargets = linkedMapOf<String, DlnaTarget>()
            locations.forEach { location ->
                currentCoroutineContext().ensureActive()
                runCatching { readTarget(location) }
                    .onSuccess { target -> target?.let { discoveredTargets[it.public.id] = it } }
                    .onFailure { error ->
                        if (error is CancellationException) throw error
                        AppLog.warning(
                            category = "cast",
                            event = "device_description_failed",
                            message = "Failed to read a discovered DLNA device",
                            throwable = error,
                        )
                    }
            }
            discoveredTargets
        }

    override suspend fun play(
        deviceId: String,
        mediaUrl: String,
        title: String,
        positionMs: Long,
        fallbackMediaUrl: String?,
        mediaProfile: CastMediaProfile,
        queue: List<CastQueueEntry>,
        queueIndex: Int,
    ): Boolean {
        val usableFallback = fallbackMediaUrl?.takeIf { castMediaUrlError(it) == null }
        val mediaError = castMediaUrlError(mediaUrl)
        if (mediaError != null && usableFallback == null) {
            mutableState.update { current ->
                if (current.hasActiveSession) {
                    current.copy(error = mediaError)
                } else {
                    current.commandFailed(mediaError)
                }
            }
            return false
        }
        val resolvedMediaUrl = if (mediaError == null) mediaUrl else requireNotNull(usableFallback)
        val resolvedProfile = if (resolvedMediaUrl == mediaUrl) mediaProfile else CastMediaProfile()
        return if (deviceId.startsWith(CAST_PREFIX)) {
            playChromecast(
                deviceId = deviceId,
                mediaUrl = resolvedMediaUrl,
                fallbackMediaUrl = usableFallback,
                title = title,
                positionMs = positionMs,
                mediaProfile = resolvedProfile,
                queue = queue,
                queueIndex = queueIndex,
            )
        } else {
            val dlnaUrl = usableFallback ?: resolvedMediaUrl
            playDlna(
                deviceId = deviceId,
                mediaUrl = dlnaUrl,
                title = title,
                positionMs = positionMs,
                // Container and size describe the original file only; a transcode keeps the length.
                mediaProfile =
                    if (dlnaUrl == mediaUrl) {
                        mediaProfile
                    } else {
                        CastMediaProfile(durationMs = mediaProfile.durationMs)
                    },
            )
        }
    }

    override suspend fun resume(): Boolean =
        when (activeProtocol) {
            ActiveProtocol.Chromecast -> chromecastCommand("继续播放") { it.play() }
            ActiveProtocol.Dlna ->
                dlnaTransportCommand(
                    action = "Play",
                    arguments = "<InstanceID>0</InstanceID><Speed>1</Speed>",
                    accepted = setOf(CastPlaybackStatus.Playing, CastPlaybackStatus.Buffering),
                )
            null -> false
        }

    override suspend fun pause(): Boolean =
        when (activeProtocol) {
            ActiveProtocol.Chromecast -> chromecastCommand("暂停") { it.pause() }
            ActiveProtocol.Dlna ->
                dlnaTransportCommand(
                    action = "Pause",
                    arguments = "<InstanceID>0</InstanceID>",
                    accepted = setOf(CastPlaybackStatus.Paused),
                )
            null -> false
        }

    override suspend fun seekTo(positionMs: Long): Boolean =
        when (activeProtocol) {
            ActiveProtocol.Chromecast ->
                chromecastCommand("跳转") {
                    it.seek(
                        MediaSeekOptions
                            .Builder()
                            .setPosition(positionMs.coerceAtLeast(0L))
                            .build(),
                    )
                }
            ActiveProtocol.Dlna -> seekDlna(positionMs)
            null -> false
        }

    override suspend fun setVolume(volume: Float): Boolean =
        when (activeProtocol) {
            ActiveProtocol.Chromecast ->
                chromecastCommand("调节音量") {
                    it.setStreamVolume(volume.coerceIn(0f, 1f).toDouble())
                }
            ActiveProtocol.Dlna -> setDlnaVolume(volume)
            null -> false
        }

    override suspend fun selectTrack(
        kind: CastTrackKind,
        language: String?,
        label: String,
        enabled: Boolean,
    ): Boolean {
        if (activeProtocol != ActiveProtocol.Chromecast) return false
        return chromecastCommand("切换${if (kind == CastTrackKind.Audio) "音轨" else "字幕"}") { remote ->
            val mediaTracks = remote.mediaInfo?.mediaTracks.orEmpty()
            val targetType =
                if (kind == CastTrackKind.Audio) MediaTrack.TYPE_AUDIO else MediaTrack.TYPE_TEXT
            val activeIds =
                remote.mediaStatus
                    ?.activeTrackIds
                    ?.toMutableSet()
                    .orEmpty()
                    .toMutableSet()
            mediaTracks.filter { it.type == targetType }.forEach { activeIds.remove(it.id) }
            if (enabled) {
                val normalizedLanguage = language?.trim()?.lowercase()
                val normalizedLabel = label.trim().lowercase()
                val target =
                    mediaTracks
                        .filter { it.type == targetType }
                        .maxByOrNull { track ->
                            when {
                                normalizedLanguage != null && track.language?.lowercase() == normalizedLanguage -> 3
                                track.name?.trim()?.lowercase() == normalizedLabel -> 2
                                track.name?.lowercase()?.contains(normalizedLabel) == true -> 1
                                else -> 0
                            }
                        }?.takeIf { track ->
                            normalizedLanguage == null ||
                                track.language?.lowercase() == normalizedLanguage ||
                                track.name?.lowercase()?.contains(normalizedLabel) == true
                        }
                requireNotNull(target) { "接收端未提供匹配轨道" }
                activeIds += target.id
            }
            remote.setActiveMediaTracks(activeIds.toLongArray())
        }
    }

    override suspend fun queueNext(): Boolean =
        when (activeProtocol) {
            ActiveProtocol.Chromecast -> chromecastCommand("下一集") { it.queueNext(null) }
            else -> false
        }

    override suspend fun queuePrevious(): Boolean =
        when (activeProtocol) {
            ActiveProtocol.Chromecast -> chromecastCommand("上一集") { it.queuePrev(null) }
            else -> false
        }

    override suspend fun stop(): Boolean =
        when (activeProtocol) {
            ActiveProtocol.Chromecast -> stopChromecast()
            ActiveProtocol.Dlna -> stopDlna()
            null -> {
                closeDlnaRelay()
                mutableState.update { it.userStopped() }
                true
            }
        }

    private fun ensureCastCallbacks() {
        if (sessionListenerRegistered) return
        CastContext.getSharedInstance(context).sessionManager.addSessionManagerListener(
            sessionListener,
            CastSession::class.java,
        )
        sessionListenerRegistered = true
    }

    private fun refreshCastRoutes() {
        castRoutes.clear()
        mediaRouter.routes
            .filter { !it.isDefault && it.matchesSelector(castSelector) }
            .forEach { castRoutes[CAST_PREFIX + it.id] = it }
        mutableState.update { it.copy(devices = allDevices()) }
    }

    private fun allDevices(): List<CastDevice> =
        targets.values.map(DlnaTarget::public) +
            castRoutes.map { (id, route) ->
                CastDevice(id, "${route.name} · Chromecast")
            }

    private suspend fun playChromecast(
        deviceId: String,
        mediaUrl: String,
        fallbackMediaUrl: String?,
        title: String,
        positionMs: Long,
        mediaProfile: CastMediaProfile,
        queue: List<CastQueueEntry>,
        queueIndex: Int,
    ): Boolean =
        withContext(Dispatchers.Main.immediate) {
            ensureCastCallbacks()
            val route = castRoutes[deviceId]
            val device = allDevices().firstOrNull { it.id == deviceId }
            if (route == null || device == null) {
                mutableState.update { it.commandFailed("Chromecast 设备已离线，请重新发现") }
                return@withContext false
            }
            val previous = mutableState.value
            val previousProtocol = activeProtocol
            val previousDlnaTarget = previous.activeDeviceId?.let(targets::get)
            suppressNextSessionEnd = false
            mutableState.value = previous.connectingTo(device, positionMs)
            val token = mutableState.value.castSessionToken()
            runCatching {
                mediaRouter.selectRoute(route)
                val castContext = CastContext.getSharedInstance(context)
                var session = castContext.sessionManager.currentCastSession
                var attempts = 0
                while (session?.remoteMediaClient == null && attempts < 20) {
                    delay(250L)
                    session = castContext.sessionManager.currentCastSession
                    attempts++
                }
                if (!token.matches(mutableState.value)) return@withContext false
                val activeSession = requireNotNull(session) { "Chromecast 会话建立超时" }
                val remote = requireNotNull(activeSession.remoteMediaClient) { "Chromecast 媒体通道不可用" }
                attachCastClient(activeSession)

                val revision = token.revision
                requestReceiverCapabilities(activeSession, revision, mediaProfile)
                if (!token.matches(mutableState.value)) return@withContext false
                val receiverCapabilities = mutableState.value.capabilities
                val canUseOriginalDolby =
                    hasYfuseCastReceiver() &&
                        (mediaProfile.dolbyVision || mediaProfile.dolbyAtmos) &&
                        receiverCapabilities.receiverConfirmed &&
                        receiverCapabilities.requestedMedia == CastCapability.Supported &&
                        (!mediaProfile.dolbyVision || receiverCapabilities.dolbyVision == CastCapability.Supported) &&
                        (!mediaProfile.dolbyAtmos || receiverCapabilities.dolbyAtmos == CastCapability.Supported)
                val selectedUrl =
                    if (canUseOriginalDolby) {
                        mediaUrl
                    } else {
                        fallbackMediaUrl?.takeIf { castMediaUrlError(it) == null } ?: mediaUrl
                    }
                val selectedProfile =
                    if (selectedUrl == mediaUrl) {
                        mediaProfile
                    } else {
                        CastMediaProfile(contentType = selectedUrl.contentType())
                    }

                val info =
                    castMediaInfo(
                        mediaUrl = selectedUrl,
                        title = title,
                        profile = selectedProfile,
                        revision = revision,
                        queueIndex = queueIndex,
                    )
                mutableState.update { it.copy(status = CastPlaybackStatus.Buffering) }
                val usableQueue =
                    queue
                        .mapIndexedNotNull { index, entry ->
                            val entryUrl =
                                entry.fallbackMediaUrl
                                    ?.takeIf { castMediaUrlError(it) == null }
                                    ?: entry.mediaUrl.takeIf { castMediaUrlError(it) == null }
                                    ?: return@mapIndexedNotNull null
                            MediaQueueItem
                                .Builder(
                                    if (index == queueIndex) {
                                        info
                                    } else {
                                        castMediaInfo(
                                            mediaUrl = entryUrl,
                                            title = entry.title,
                                            profile =
                                                if (entryUrl == entry.mediaUrl) {
                                                    entry.mediaProfile
                                                } else {
                                                    CastMediaProfile(contentType = entryUrl.contentType())
                                                },
                                            revision = revision,
                                            queueIndex = index,
                                        )
                                    },
                                ).setAutoplay(true)
                                .build()
                        }.takeIf { items -> items.size == queue.size && queueIndex in items.indices }
                val accepted =
                    withTimeoutOrNull(CAST_COMMAND_TIMEOUT_MS) {
                        if (usableQueue != null && usableQueue.size > 1) {
                            remote
                                .queueLoad(
                                    usableQueue.toTypedArray(),
                                    queueIndex,
                                    MediaStatus.REPEAT_MODE_REPEAT_OFF,
                                    positionMs.coerceAtLeast(0L),
                                    null,
                                ).awaitSuccess()
                        } else {
                            remote
                                .load(
                                    MediaLoadRequestData
                                        .Builder()
                                        .setMediaInfo(info)
                                        .setAutoplay(true)
                                        .setCurrentTime(positionMs.coerceAtLeast(0L))
                                        .build(),
                                ).awaitSuccess()
                        }
                    } == true
                check(accepted) { "接收端拒绝加载媒体" }
                if (!token.matches(mutableState.value)) return@withContext false
                activeProtocol = ActiveProtocol.Chromecast
                dlnaPollJob?.cancel()
                closeDlnaRelay()
                mutableState.update { it.remoteUpdate(CastPlaybackStatus.Buffering) }
                remote.requestStatus()
                syncChromecastStatus()
                true
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                if (!token.matches(mutableState.value)) return@withContext false
                AppLog.error("cast", "chromecast_play_failed", "Chromecast playback failed", error)
                activeProtocol = previousProtocol
                mutableState.value =
                    restoreCastSessionAfterFailedLoad(
                        previous = previous,
                        failed = mutableState.value,
                        message = "Chromecast 投屏失败：${error.message ?: "连接失败"}",
                    )
                if (previousProtocol == ActiveProtocol.Dlna) {
                    detachCastClient()
                } else if (previousProtocol == ActiveProtocol.Chromecast) {
                    CastContext
                        .getSharedInstance(context)
                        .sessionManager.currentCastSession
                        ?.let(::attachCastClient)
                } else {
                    detachCastClient()
                }
                if (previousProtocol == ActiveProtocol.Dlna) previousDlnaTarget?.let(::startDlnaPolling)
                false
            }
        }

    private fun attachCastClient(session: CastSession) {
        val remote = session.remoteMediaClient ?: return
        if (castClient === remote) return
        detachCastClient()
        castClient = remote
        remote.registerCallback(castCallback)
        remote.addProgressListener(castProgressListener, CAST_PROGRESS_INTERVAL_MS)
        if (hasYfuseCastReceiver()) {
            runCatching {
                session.setMessageReceivedCallbacks(CAST_OUTPUT_NAMESPACE, castMessageCallback)
                castMessageSession = session
            }.onFailure { error ->
                AppLog.warning(
                    category = "cast",
                    event = "receiver_channel_attach_failed",
                    message = "Cast output channel unavailable",
                    throwable = error,
                )
            }
        }
        if (activeProtocol == ActiveProtocol.Chromecast) syncChromecastStatus()
    }

    private fun detachCastClient() {
        castMessageSession?.let { session ->
            runCatching { session.removeMessageReceivedCallbacks(CAST_OUTPUT_NAMESPACE) }
        }
        castMessageSession = null
        castClient?.let { client ->
            runCatching { client.unregisterCallback(castCallback) }
            runCatching { client.removeProgressListener(castProgressListener) }
        }
        castClient = null
    }

    private suspend fun requestReceiverCapabilities(
        session: CastSession,
        revision: Long,
        profile: CastMediaProfile,
    ) {
        if (!hasYfuseCastReceiver()) return
        val request =
            JSONObject()
                .put("type", "capabilities.request")
                .put("revision", revision)
                .put("profile", profile.toJson())
                .toString()
        val sent =
            withTimeoutOrNull(CAST_CAPABILITY_TIMEOUT_MS) {
                session.sendMessage(CAST_OUTPUT_NAMESPACE, request)?.awaitSuccess() == true
            } == true
        if (!sent) return
        withTimeoutOrNull(CAST_CAPABILITY_TIMEOUT_MS) {
            while (
                mutableState.value.sessionRevision == revision &&
                !mutableState.value.capabilities.receiverConfirmed
            ) {
                delay(CAST_CAPABILITY_POLL_MS)
            }
        }
    }

    private fun handleCastReceiverMessage(message: String) {
        val payload = runCatching { JSONObject(message) }.getOrNull() ?: return
        val revision = payload.optLong("revision", -1L).takeIf { it >= 0L } ?: return
        when (payload.optString("type")) {
            "capabilities.response" ->
                mutableState.update { state ->
                    state.withReceiverCapabilities(
                        revision = revision,
                        dolbyVision = payload.castCapability("dolbyVisionSupported"),
                        dolbyAtmos = payload.castCapability("dolbyAtmosSupported"),
                        requestedMedia = payload.castCapability("requestedMediaSupported"),
                        trackSelection = payload.castCapability("trackSelectionSupported"),
                        queue = payload.castCapability("queueSupported"),
                    )
                }
            "output.receipt" ->
                mutableState.update { state ->
                    state.withReceiverOutputReceipt(
                        revision = revision,
                        playbackConfirmed = payload.optBoolean("playbackConfirmed", false),
                        dolbyVisionOutput = payload.optBoolean("dolbyVisionOutput", false),
                        dolbyAtmosOutput = payload.optBoolean("dolbyAtmosOutput", false),
                        detail = payload.optString("detail", "Cast 接收端输出回执"),
                    )
                }
            "session.state" ->
                mutableState.update { state ->
                    if (revision != state.sessionRevision || state.termination != null) {
                        state
                    } else {
                        state.remoteUpdate(
                            status = state.status,
                            queueSize = payload.optInt("queueSize", state.queueSize),
                            currentQueueIndex =
                                payload.optInt("queueIndex", state.currentQueueIndex),
                        )
                    }
                }
        }
    }

    private fun syncChromecastStatus() {
        val remote = castClient ?: return
        if (
            activeProtocol != ActiveProtocol.Chromecast ||
            mutableState.value.activeDeviceId?.startsWith(CAST_PREFIX) != true ||
            !remote.hasMediaSession()
        ) {
            return
        }
        val mediaStatus = remote.mediaStatus ?: return
        val playbackStatus =
            when (mediaStatus.playerState) {
                MediaStatus.PLAYER_STATE_PLAYING -> CastPlaybackStatus.Playing
                MediaStatus.PLAYER_STATE_PAUSED -> CastPlaybackStatus.Paused
                MediaStatus.PLAYER_STATE_BUFFERING,
                MediaStatus.PLAYER_STATE_LOADING,
                -> CastPlaybackStatus.Buffering
                MediaStatus.PLAYER_STATE_IDLE ->
                    when (mediaStatus.idleReason) {
                        MediaStatus.IDLE_REASON_FINISHED -> CastPlaybackStatus.Ended
                        MediaStatus.IDLE_REASON_ERROR -> CastPlaybackStatus.Error
                        else -> CastPlaybackStatus.Paused
                    }
                else -> CastPlaybackStatus.Buffering
            }
        val capabilities =
            mutableState.value.capabilities.copy(
                playPause = CastCapability.Supported,
                seek =
                    if (mediaStatus.isMediaCommandSupported(MediaStatus.COMMAND_SEEK)) {
                        CastCapability.Supported
                    } else {
                        CastCapability.Unsupported
                    },
                stop = CastCapability.Supported,
                volume =
                    if (mediaStatus.isMediaCommandSupported(MediaStatus.COMMAND_SET_VOLUME)) {
                        CastCapability.Supported
                    } else {
                        CastCapability.Unsupported
                    },
                trackSelection =
                    if (
                        remote.mediaInfo
                            ?.mediaTracks
                            .orEmpty()
                            .isNotEmpty()
                    ) {
                        CastCapability.Supported
                    } else {
                        mutableState.value.capabilities.trackSelection
                    },
                queue =
                    if (mediaStatus.queueItemCount > 1) {
                        CastCapability.Supported
                    } else {
                        mutableState.value.capabilities.queue
                    },
            )
        if (playbackStatus == CastPlaybackStatus.Error) {
            mutableState.update { it.commandFailed("Chromecast 接收端报告播放错误") }
            return
        }
        val activeTrackIds = mediaStatus.activeTrackIds?.toSet().orEmpty()
        val tracks =
            remote.mediaInfo?.mediaTracks.orEmpty().mapNotNull { track ->
                val kind =
                    when (track.type) {
                        MediaTrack.TYPE_AUDIO -> CastTrackKind.Audio
                        MediaTrack.TYPE_TEXT -> CastTrackKind.Subtitle
                        else -> return@mapNotNull null
                    }
                CastTrack(
                    id = track.id,
                    kind = kind,
                    label = track.name ?: track.language ?: "轨道 ${track.id}",
                    language = track.language,
                    selected = track.id in activeTrackIds,
                )
            }
        val currentQueueIndex =
            remote.mediaInfo?.customData?.optInt("yfuseQueueIndex", 0) ?: 0
        mutableState.update { state ->
            val updated =
                state.remoteUpdate(
                    status = playbackStatus,
                    positionMs = remote.approximateStreamPosition,
                    durationMs = remote.streamDuration,
                    volume = mediaStatus.streamVolume.toFloat(),
                    capabilities = capabilities,
                    queueSize = mediaStatus.queueItemCount,
                    currentQueueIndex = currentQueueIndex,
                    tracks = tracks,
                )
            if (currentQueueIndex != state.currentQueueIndex) {
                updated.copy(
                    outputEvidence = CastOutputEvidence(sessionRevision = state.sessionRevision),
                )
            } else {
                updated
            }
        }
    }

    private suspend fun chromecastCommand(
        label: String,
        command: (RemoteMediaClient) -> PendingResult<out Result>,
    ): Boolean =
        withContext(Dispatchers.Main.immediate) {
            val remote =
                castClient
                    ?: CastContext
                        .getSharedInstance(context)
                        .sessionManager.currentCastSession
                        ?.remoteMediaClient
            if (remote == null || activeProtocol != ActiveProtocol.Chromecast) {
                mutableState.update { it.commandFailed("Chromecast 会话已不可用") }
                return@withContext false
            }
            val accepted =
                runCatching {
                    withTimeoutOrNull(CAST_COMMAND_TIMEOUT_MS) { command(remote).awaitSuccess() } == true
                }.getOrDefault(false)
            if (!accepted) {
                mutableState.update { it.commandFailed("Chromecast ${label}失败") }
                return@withContext false
            }
            remote.requestStatus()
            syncChromecastStatus()
            true
        }

    private suspend fun stopChromecast(): Boolean =
        withContext(Dispatchers.Main.immediate) {
            val remote =
                castClient
                    ?: CastContext
                        .getSharedInstance(context)
                        .sessionManager.currentCastSession
                        ?.remoteMediaClient
            val accepted =
                remote == null ||
                    withTimeoutOrNull(CAST_COMMAND_TIMEOUT_MS) { remote.stop().awaitSuccess() } == true
            if (!accepted) {
                mutableState.update { it.commandFailed("Chromecast 停止失败") }
                return@withContext false
            }
            suppressNextSessionEnd = true
            detachCastClient()
            CastContext.getSharedInstance(context).sessionManager.endCurrentSession(true)
            activeProtocol = null
            mutableState.update { it.userStopped() }
            true
        }

    private suspend fun playDlna(
        deviceId: String,
        mediaUrl: String,
        title: String,
        positionMs: Long,
        mediaProfile: CastMediaProfile,
    ): Boolean =
        withContext(Dispatchers.Main.immediate) {
            val target = targets[deviceId]
            if (target == null) {
                mutableState.update { it.commandFailed("DLNA 设备已离线，请重新发现") }
                return@withContext false
            }
            val previous = mutableState.value
            val previousProtocol = activeProtocol
            val previousDlnaTarget = previous.activeDeviceId?.let(targets::get)
            val previousStart = dlnaStart
            if (previousProtocol == ActiveProtocol.Dlna) {
                dlnaPollJob?.cancel()
                dlnaPollJob = null
            }
            mutableState.value = previous.connectingTo(target.public, positionMs)
            val token = mutableState.value.castSessionToken()
            val format = dlnaMediaFormat(mediaUrl, mediaProfile.container)
            val currentRelay = dlnaRelay
            var route: DlnaRoute? = null
            val result =
                try {
                    readDlnaSessionResult(token, { mutableState.value }) {
                        val prepared = prepareDlnaRoute(target, mediaUrl, format, currentRelay).also { route = it }
                        AppLog.info(
                            category = "cast",
                            event = "dlna_load_requested",
                            message = "DLNA load requested",
                            attributes =
                                mapOf(
                                    "route" to if (prepared.relay != null) "relay" else "direct",
                                    "relayReason" to (prepared.reason ?: "none"),
                                    "mimeType" to format.mimeType,
                                    "byteSeekable" to format.byteSeekable.toString(),
                                    "startPositionMs" to positionMs.toString(),
                                    "durationKnown" to (mediaProfile.durationMs != null).toString(),
                                    "sizeKnown" to (mediaProfile.sizeBytes != null).toString(),
                                ),
                        )
                        soap(
                            target.avTransportUrl,
                            "SetAVTransportURI",
                            "<InstanceID>0</InstanceID>" +
                                "<CurrentURI>${prepared.url.dlnaXmlEscape()}</CurrentURI>" +
                                "<CurrentURIMetaData>" +
                                dlnaMetadata(
                                    url = prepared.url,
                                    title = title,
                                    format = format,
                                    durationMs = mediaProfile.durationMs,
                                    sizeBytes = mediaProfile.sizeBytes,
                                ).dlnaXmlEscape() +
                                "</CurrentURIMetaData>",
                        )

                        val seekCapability = queryDlnaSeekCapability(target)
                        var seekedBeforePlay = false
                        if (positionMs > 0L && seekCapability == CastCapability.Supported) {
                            runCatching {
                                soap(
                                    target.avTransportUrl,
                                    "Seek",
                                    "<InstanceID>0</InstanceID><Unit>REL_TIME</Unit>" +
                                        "<Target>${formatDlnaTime(positionMs)}</Target>",
                                )
                            }.onSuccess {
                                seekedBeforePlay = true
                            }.onFailure { error ->
                                if (error is CancellationException) throw error
                                AppLog.warning("cast", "dlna_initial_seek_failed", "DLNA initial seek failed", error)
                            }
                        }
                        soap(
                            target.avTransportUrl,
                            "Play",
                            "<InstanceID>0</InstanceID><Speed>1</Speed>",
                        )
                        val confirmed =
                            confirmDlnaTransport(
                                target = target,
                                accepted = setOf(CastPlaybackStatus.Playing, CastPlaybackStatus.Buffering),
                            ) ?: error("设备未确认开始播放")
                        DlnaLoad(confirmed, seekCapability, seekedBeforePlay)
                    }
                } catch (cancelled: CancellationException) {
                    route?.relay?.takeIf { it !== dlnaRelay }?.close()
                    throw cancelled
                } ?: run {
                    // A newer load or a stop took over; a relay opened for this one is not kept.
                    route?.relay?.takeIf { it !== dlnaRelay }?.close()
                    return@withContext false
                }
            result.fold(onSuccess = { load ->
                val accepted = requireNotNull(route)
                activeProtocol = ActiveProtocol.Dlna
                detachCastClient()
                adoptDlnaRelay(accepted, mediaUrl, format, target)
                // Most renderers list no Seek while STOPPED, which is where it was asked; only a yes
                // from there is an answer. The poller asks again once the renderer is playing.
                val seekCapability =
                    load.seekCapability.takeIf { it == CastCapability.Supported } ?: CastCapability.Unknown
                val capabilities =
                    CastCapabilities(
                        playPause = CastCapability.Supported,
                        seek = seekCapability,
                        stop = CastCapability.Supported,
                        volume =
                            if (target.renderingControlUrl == null) {
                                CastCapability.Unsupported
                            } else {
                                CastCapability.Unknown
                            },
                    )
                mutableState.update {
                    // Accepted is not yet playing: the poller says 播放中 once the renderer shows it.
                    it
                        .remoteUpdate(
                            status = CastPlaybackStatus.Buffering,
                            durationMs = load.snapshot.durationMs,
                            capabilities = capabilities,
                        ).copy(lastRemoteWasPlaying = true, relayed = accepted.relay != null)
                }
                AppLog.info(
                    category = "cast",
                    event = "dlna_load_accepted",
                    message = "DLNA renderer accepted the load",
                    attributes =
                        mapOf(
                            "transportState" to load.snapshot.transportState,
                            "positionMs" to (load.snapshot.positionMs?.toString() ?: "unknown"),
                            "durationMs" to (load.snapshot.durationMs?.toString() ?: "unknown"),
                            "seekCapability" to load.seekCapability.name,
                            "seekedBeforePlay" to load.seekedBeforePlay.toString(),
                        ),
                )
                val now = monotonicNowMs()
                startDlnaPolling(
                    target,
                    DlnaStart(
                        targetPositionMs = positionMs.coerceAtLeast(0L),
                        monitor = DlnaStartMonitor(startedAtMs = now),
                        startedAtMs = now,
                        seekCapability = seekCapability,
                        seekAttempts = if (load.seekedBeforePlay) 1 else 0,
                    ),
                )
                true
            }, onFailure = { error ->
                AppLog.error(
                    category = "cast",
                    event = "dlna_play_failed",
                    message = "DLNA playback request failed",
                    throwable = error,
                    attributes = mapOf("route" to if (route?.relay != null) "relay" else "direct"),
                )
                activeProtocol = previousProtocol
                mutableState.value =
                    restoreCastSessionAfterFailedLoad(
                        previous = previous,
                        failed = mutableState.value,
                        message = "投屏设备没有开始播放，请确认设备在线后重试，或换一台设备",
                    )
                releaseFailedDlnaRoute(
                    route,
                    previousStands = previousProtocol == ActiveProtocol.Dlna && previous.hasActiveSession,
                )
                dlnaStart = previousStart
                if (previousProtocol == ActiveProtocol.Dlna) previousDlnaTarget?.let { startDlnaPolling(it) }
                false
            })
        }

    /**
     * The address to give [target] for [mediaUrl]. A plain-HTTP server on the local network is
     * read directly, as the renderer reads any other media server there; anything else goes through
     * [DlnaMediaRelay], and stays direct only if the relay cannot be opened.
     */
    private suspend fun prepareDlnaRoute(
        target: DlnaTarget,
        mediaUrl: String,
        format: DlnaMediaFormat,
        currentRelay: DlnaMediaRelay?,
    ): DlnaRoute =
        withContext(Dispatchers.IO) {
            val reason = dlnaRelayReason(mediaUrl) ?: return@withContext DlnaRoute(mediaUrl, null, null, null)
            var opened: DlnaMediaRelay? = null
            try {
                val renderer = InetAddress.getByName(URI(target.public.id).host)
                val local = requireNotNull(localAddressFacing(renderer)) { "No local route to the renderer" }
                val relay =
                    currentRelay?.takeIf { it.isOpen && it.bindAddress == local }
                        ?: DlnaMediaRelay(
                            bindAddress = local,
                            baseClient = PlaybackHttpDataSource.client,
                            userAgent = ::playbackUserAgent,
                            nowMs = ::monotonicNowMs,
                        ).also { opened = it }
                val url = requireNotNull(relay.publish(mediaUrl, format, renderer)) { "DLNA relay closed" }
                DlnaRoute(url, relay, reason, renderer)
            } catch (cancelled: CancellationException) {
                opened?.close()
                throw cancelled
            } catch (error: Exception) {
                opened?.close()
                AppLog.warning(
                    category = "cast",
                    event = "dlna_relay_unavailable",
                    message = "DLNA relay could not be opened; the renderer reads the server directly",
                    throwable = error,
                    attributes = mapOf("relayReason" to reason),
                )
                DlnaRoute(mediaUrl, null, reason, null)
            }
        }

    private fun playbackUserAgent(): String =
        userAgentPreferences
            ?.userAgent
            ?.value
            ?.takeIf(String::isNotBlank) ?: DEFAULT_EMBY_USER_AGENT

    /** The accepted load's relay becomes the session's; a relay it no longer needs is closed. */
    private fun adoptDlnaRelay(
        route: DlnaRoute,
        upstreamUrl: String,
        format: DlnaMediaFormat,
        target: DlnaTarget,
    ) {
        val relay = route.relay
        val renderer = route.renderer
        if (relay !== dlnaRelay) dlnaRelay?.close()
        dlnaRelay = relay
        if (relay == null || renderer == null) {
            dlnaRelayMedia = null
            CastRelayService.stop(context)
        } else {
            dlnaRelayMedia = DlnaRelayMedia(upstreamUrl, format, renderer)
            CastRelayService.start(context, target.public.name)
        }
    }

    /**
     * A failed load published its media on the session's relay too. When the previous session
     * stands, its media is published again, under the path its renderer is still reading.
     */
    private fun releaseFailedDlnaRoute(
        route: DlnaRoute?,
        previousStands: Boolean,
    ) {
        val relay = route?.relay ?: return
        if (relay !== dlnaRelay) {
            relay.close()
            return
        }
        val media = dlnaRelayMedia
        if (previousStands && media != null) {
            relay.publish(media.upstreamUrl, media.format, media.renderer)
        } else {
            closeDlnaRelay()
        }
    }

    /**
     * A finished film leaves the session in place for 下一集 and for the controls, but nothing reads
     * the relay any more; holding the CPU and Wi-Fi awake for it would last until someone stops casting.
     */
    private fun releaseRelayAfterEnd(status: CastPlaybackStatus) {
        if (status != CastPlaybackStatus.Ended || dlnaRelay == null) {
            dlnaEndedSinceMs = null
            return
        }
        val now = monotonicNowMs()
        val since = dlnaEndedSinceMs ?: now.also { dlnaEndedSinceMs = it }
        if (now - since < DLNA_RELAY_ENDED_RELEASE_MS) return
        AppLog.info("cast", "dlna_relay_released", "DLNA relay closed after the renderer finished the media")
        closeDlnaRelay()
        mutableState.update { it.copy(relayed = false) }
    }

    private fun closeDlnaRelay() {
        dlnaStart = null
        dlnaEndedSinceMs = null
        val relay = dlnaRelay ?: return
        dlnaRelay = null
        dlnaRelayMedia = null
        relay.close()
        CastRelayService.stop(context)
    }

    private fun startDlnaPolling(
        target: DlnaTarget,
        start: DlnaStart? = null,
    ) {
        dlnaPollJob?.cancel()
        if (start != null) dlnaStart = start
        val token = mutableState.value.castSessionToken()
        dlnaPollJob =
            scope.launch {
                var failures = 0
                var lastTransportState: String? = null
                while (
                    isActive &&
                    activeProtocol == ActiveProtocol.Dlna &&
                    token.matches(mutableState.value)
                ) {
                    delay(
                        if (dlnaStart != null || mutableState.value.status == CastPlaybackStatus.Playing) {
                            DLNA_ACTIVE_POLL_INTERVAL_MS
                        } else {
                            DLNA_IDLE_POLL_INTERVAL_MS
                        },
                    )
                    val result =
                        readDlnaSessionResult(token, { mutableState.value }) { readDlnaSnapshot(target) }
                            ?: return@launch
                    result
                        .onSuccess { snapshot ->
                            failures = 0
                            if (snapshot.transportState != lastTransportState) {
                                lastTransportState = snapshot.transportState
                                logDlnaTransportState(snapshot, dlnaStart != null)
                            }
                            if (snapshot.status == CastPlaybackStatus.Error) {
                                mutableState.update { it.commandFailed("DLNA 接收端报告未知播放状态") }
                                return@launch
                            }
                            val pending = dlnaStart
                            if (pending == null) {
                                releaseRelayAfterEnd(snapshot.status)
                                if (dlnaClockWatch?.moved(snapshot.status, snapshot.positionMs) == true) {
                                    dlnaClockTrusted = true
                                    dlnaClockWatch = null
                                }
                                mutableState.update {
                                    it.remoteUpdate(
                                        status = snapshot.status,
                                        positionMs = snapshot.positionMs.takeIf { dlnaClockTrusted },
                                        durationMs = snapshot.durationMs,
                                    )
                                }
                                return@onSuccess
                            }
                            val activity = dlnaRelay?.activity()
                            when (
                                val verdict =
                                    pending.monitor.observe(
                                        status = snapshot.status,
                                        positionMs = snapshot.positionMs,
                                        nowMs = monotonicNowMs(),
                                        relay = activity,
                                    )
                            ) {
                                DlnaStartVerdict.Waiting -> {
                                    if (snapshot.status == CastPlaybackStatus.Playing) {
                                        seekToStart(target, token, pending, snapshot.positionMs, maxAttempts = 1)
                                    }
                                    mutableState.update {
                                        it.remoteUpdate(
                                            status =
                                                if (snapshot.status == CastPlaybackStatus.Paused) {
                                                    CastPlaybackStatus.Paused
                                                } else {
                                                    CastPlaybackStatus.Buffering
                                                },
                                            durationMs = snapshot.durationMs,
                                        )
                                    }
                                }
                                is DlnaStartVerdict.Started -> {
                                    dlnaStart = null
                                    dlnaClockTrusted = verdict.positionMs != null
                                    dlnaClockWatch = DlnaClockWatch().takeUnless { dlnaClockTrusted }
                                    logDlnaStart(pending, snapshot, activity, failure = null)
                                    // A renderer that refused Seek while it was still opening the file.
                                    if (dlnaClockTrusted) {
                                        seekToStart(
                                            target,
                                            token,
                                            pending,
                                            verdict.positionMs,
                                            maxAttempts = DLNA_START_SEEK_ATTEMPTS,
                                        )
                                    }
                                    mutableState.update {
                                        it.remoteUpdate(
                                            status = snapshot.status,
                                            positionMs = verdict.positionMs,
                                            durationMs = snapshot.durationMs,
                                        )
                                    }
                                }
                                is DlnaStartVerdict.Failed -> {
                                    logDlnaStart(pending, snapshot, activity, verdict.failure)
                                    failDlnaStart(target, token, verdict.failure)
                                    return@launch
                                }
                            }
                        }.onFailure { error ->
                            mutableState.update { current ->
                                current.copy(
                                    capabilities =
                                        current.capabilities.copy(
                                            seek = CastCapability.Unknown,
                                        ),
                                )
                            }
                            failures++
                            if (error is CastHttpException && error.statusCode in setOf(401, 403)) {
                                markUnexpectedDisconnect("DLNA 设备拒绝访问（${error.statusCode}）")
                                return@launch
                            }
                            if (failures >= DLNA_MAX_POLL_FAILURES) {
                                markUnexpectedDisconnect("DLNA 设备连续无响应")
                                return@launch
                            }
                        }
                }
            }
    }

    /**
     * Moves a renderer that started from the top to where this phone was. Before Play most renderers
     * list no Seek, so the first chance is the first PLAYING; a renderer that refuses while it is still
     * opening the file gets one more once its clock moves. Without a Seek it plays from the top, and
     * the handoff follows its real position from then on.
     */
    private suspend fun seekToStart(
        target: DlnaTarget,
        token: CastSessionToken,
        start: DlnaStart,
        positionMs: Long?,
        maxAttempts: Int,
    ) {
        val targetMs = start.targetPositionMs
        if (targetMs <= DLNA_SEEK_TOLERANCE_MS || start.seekAttempts >= maxAttempts) return
        if (positionMs != null && kotlin.math.abs(positionMs - targetMs) <= DLNA_SEEK_TOLERANCE_MS) return
        if (start.seekCapability != CastCapability.Supported) {
            val capability =
                readDlnaSessionResult(token, { mutableState.value }) { queryDlnaSeekCapability(target) }
                    ?.getOrNull() ?: return
            start.seekCapability = capability
            mutableState.update { it.copy(capabilities = it.capabilities.copy(seek = capability)) }
            if (capability == CastCapability.Unsupported) {
                start.seekAttempts = DLNA_START_SEEK_ATTEMPTS
                AppLog.info("cast", "dlna_start_seek_unsupported", "DLNA renderer cannot seek; it plays from the top")
                return
            }
        }
        start.seekAttempts++
        val sent =
            readDlnaSessionResult(token, { mutableState.value }) {
                soap(
                    target.avTransportUrl,
                    "Seek",
                    "<InstanceID>0</InstanceID><Unit>REL_TIME</Unit>" +
                        "<Target>${formatDlnaTime(targetMs)}</Target>",
                )
            } ?: return
        sent
            .onSuccess {
                start.monitor.rebase()
                AppLog.info(
                    category = "cast",
                    event = "dlna_start_seek",
                    message = "DLNA renderer moved to the phone position",
                    attributes =
                        mapOf(
                            "targetMs" to targetMs.toString(),
                            "fromMs" to (positionMs?.toString() ?: "unknown"),
                            "attempt" to start.seekAttempts.toString(),
                        ),
                )
            }.onFailure { error ->
                AppLog.warning("cast", "dlna_start_seek_failed", "DLNA renderer refused the start seek", error)
            }
    }

    /** The renderer never played this load: playback carries on here, and the viewer is told why. */
    private suspend fun failDlnaStart(
        target: DlnaTarget,
        token: CastSessionToken,
        failure: DlnaStartFailure,
    ) {
        // It may be sitting on an error screen; a load that never played loses nothing by stopping.
        readDlnaSessionResult(token, { mutableState.value }) {
            soap(target.avTransportUrl, "Stop", "<InstanceID>0</InstanceID>")
        }
        if (!token.matches(mutableState.value)) return
        // This runs inside the poll job, which ends here; cancelling it would cancel this update.
        dlnaPollJob = null
        dlnaStart = null
        activeProtocol = null
        closeDlnaRelay()
        mutableState.update { it.startFailed(dlnaStartFailureMessage(target.public.name, failure)) }
    }

    private fun logDlnaTransportState(
        snapshot: DlnaSnapshot,
        starting: Boolean,
    ) {
        AppLog.info(
            category = "cast",
            event = "dlna_transport_state",
            message = "DLNA renderer reported a new transport state",
            attributes =
                mapOf(
                    "state" to snapshot.transportState,
                    "positionMs" to (snapshot.positionMs?.toString() ?: "unknown"),
                    "durationMs" to (snapshot.durationMs?.toString() ?: "unknown"),
                    "phase" to if (starting) "starting" else "session",
                ),
        )
    }

    private fun logDlnaStart(
        start: DlnaStart,
        snapshot: DlnaSnapshot,
        activity: DlnaRelayActivity?,
        failure: DlnaStartFailure?,
    ) {
        val attributes =
            mapOf(
                "elapsedMs" to (monotonicNowMs() - start.startedAtMs).toString(),
                "state" to snapshot.transportState,
                "positionMs" to (snapshot.positionMs?.toString() ?: "unknown"),
                "relayed" to (activity != null).toString(),
                "relayRequests" to (activity?.requests?.toString() ?: "none"),
                "relayBytes" to (activity?.bytesServed?.toString() ?: "none"),
                "relayUpstreamFailures" to (activity?.upstreamFailures?.toString() ?: "none"),
                "seekAttempts" to start.seekAttempts.toString(),
            )
        if (failure == null) {
            AppLog.info("cast", "dlna_start_confirmed", "DLNA renderer is playing the load", attributes)
        } else {
            AppLog.warning(
                category = "cast",
                event = "dlna_start_failed",
                message = "DLNA renderer accepted the load but never played it",
                attributes = attributes + ("failure" to failure.diagnosticName),
            )
        }
    }

    private suspend fun dlnaTransportCommand(
        action: String,
        arguments: String,
        accepted: Set<CastPlaybackStatus>,
    ): Boolean =
        withContext(Dispatchers.Main.immediate) {
            val target = activeDlnaTarget() ?: return@withContext false
            val label = dlnaActionLabel(action)
            val result =
                readDlnaSessionResult(mutableState.value.castSessionToken(), { mutableState.value }) {
                    soap(target.avTransportUrl, action, arguments)
                    confirmDlnaTransport(target, accepted) ?: error("设备未确认$label")
                } ?: return@withContext false
            result
                .onSuccess { snapshot ->
                    val starting = dlnaStart != null
                    mutableState.update {
                        it.remoteUpdate(
                            status =
                                if (starting && snapshot.status == CastPlaybackStatus.Playing) {
                                    CastPlaybackStatus.Buffering
                                } else {
                                    snapshot.status
                                },
                            positionMs = snapshot.positionMs.takeIf { !starting && dlnaClockTrusted },
                            durationMs = snapshot.durationMs,
                        )
                    }
                }.onFailure { error ->
                    AppLog.warning("cast", "dlna_command_failed", "DLNA $action failed", error)
                    mutableState.update { it.commandFailed(dlnaNoResponse(label)) }
                }.isSuccess
        }

    private suspend fun seekDlna(positionMs: Long): Boolean =
        withContext(Dispatchers.Main.immediate) {
            val target = activeDlnaTarget() ?: return@withContext false
            val token = mutableState.value.castSessionToken()
            val capability =
                readDlnaSessionResult(token, { mutableState.value }) {
                    when (mutableState.value.capabilities.seek) {
                        CastCapability.Unknown -> queryDlnaSeekCapability(target)
                        else -> mutableState.value.capabilities.seek
                    }
                }?.getOrThrow() ?: return@withContext false
            mutableState.update { it.copy(capabilities = it.capabilities.copy(seek = capability)) }
            if (capability != CastCapability.Supported) {
                mutableState.update { it.copy(error = "此 DLNA 设备未确认支持跳转") }
                return@withContext false
            }
            val targetPosition = positionMs.coerceAtLeast(0L)
            val previousStatus = mutableState.value.status
            val result =
                readDlnaSessionResult(token, { mutableState.value }) {
                    soap(
                        target.avTransportUrl,
                        "Seek",
                        "<InstanceID>0</InstanceID><Unit>REL_TIME</Unit>" +
                            "<Target>${formatDlnaTime(targetPosition)}</Target>",
                    )
                    awaitDlnaConfirmation(
                        attempts = DLNA_CONFIRM_ATTEMPTS,
                        delayMs = DLNA_CONFIRM_DELAY_MS,
                        read = { readDlnaSnapshot(target) },
                        accepted = { snapshot ->
                            snapshot.positionMs?.let { actual ->
                                kotlin.math.abs(actual - targetPosition) <= DLNA_SEEK_TOLERANCE_MS
                            } == true
                        },
                    ) ?: error("设备未确认跳转位置")
                } ?: return@withContext false
            result
                .onSuccess { snapshot ->
                    val starting = dlnaStart
                    starting?.monitor?.rebase()
                    mutableState.update {
                        it.remoteUpdate(
                            status =
                                snapshot.status.takeUnless { value -> value == CastPlaybackStatus.Error }
                                    ?: previousStatus,
                            positionMs = snapshot.positionMs.takeIf { starting == null && dlnaClockTrusted },
                            durationMs = snapshot.durationMs,
                        )
                    }
                }.onFailure { error ->
                    AppLog.warning("cast", "dlna_command_failed", "DLNA Seek failed", error)
                    mutableState.update { it.commandFailed(dlnaNoResponse("跳转")) }
                }.isSuccess
        }

    private suspend fun setDlnaVolume(volume: Float): Boolean =
        withContext(Dispatchers.Main.immediate) {
            val target = activeDlnaTarget() ?: return@withContext false
            val controlUrl = target.renderingControlUrl
            if (controlUrl == null) {
                mutableState.update {
                    it.copy(
                        capabilities = it.capabilities.copy(volume = CastCapability.Unsupported),
                        error = "此 DLNA 设备未提供远端音量控制",
                    )
                }
                return@withContext false
            }
            val desired = (volume.coerceIn(0f, 1f) * 100f).toInt()
            val result =
                readDlnaSessionResult(mutableState.value.castSessionToken(), { mutableState.value }) {
                    soap(
                        controlUrl,
                        "SetVolume",
                        "<InstanceID>0</InstanceID><Channel>Master</Channel>" +
                            "<DesiredVolume>$desired</DesiredVolume>",
                        service = RENDERING_CONTROL_SERVICE,
                    )
                    val response =
                        soap(
                            controlUrl,
                            "GetVolume",
                            "<InstanceID>0</InstanceID><Channel>Master</Channel>",
                            service = RENDERING_CONTROL_SERVICE,
                        )
                    response.xmlTag("CurrentVolume")?.toIntOrNull()
                        ?: error("设备未返回音量")
                } ?: return@withContext false
            result
                .onSuccess { confirmed ->
                    mutableState.update {
                        it.remoteUpdate(
                            status = it.status,
                            volume = (confirmed / 100f).coerceIn(0f, 1f),
                            capabilities = it.capabilities.copy(volume = CastCapability.Supported),
                        )
                    }
                }.onFailure { error ->
                    AppLog.warning("cast", "dlna_command_failed", "DLNA SetVolume failed", error)
                    mutableState.update {
                        it.copy(
                            capabilities = it.capabilities.copy(volume = CastCapability.Unknown),
                            error = dlnaNoResponse("音量调节"),
                        )
                    }
                }.isSuccess
        }

    private suspend fun stopDlna(): Boolean =
        withContext(Dispatchers.Main.immediate) {
            val target = activeDlnaTarget() ?: return@withContext false
            val token = mutableState.value.castSessionToken()
            val stopped =
                readDlnaSessionResult(token, { mutableState.value }) {
                    soap(target.avTransportUrl, "Stop", "<InstanceID>0</InstanceID>")
                    awaitDlnaConfirmation(
                        attempts = DLNA_CONFIRM_ATTEMPTS,
                        delayMs = DLNA_CONFIRM_DELAY_MS,
                        read = { queryDlnaTransportStatus(target) },
                        accepted = { it == "STOPPED" || it == "NO_MEDIA_PRESENT" },
                    ) != null
                }?.getOrElse { error ->
                    AppLog.warning("cast", "dlna_command_failed", "DLNA Stop failed", error)
                    mutableState.update { it.commandFailed(dlnaNoResponse("停止")) }
                    false
                } ?: return@withContext false
            if (!stopped) {
                mutableState.update { it.commandFailed("DLNA 设备未确认停止") }
                return@withContext false
            }
            dlnaPollJob?.cancel()
            dlnaPollJob = null
            activeProtocol = null
            closeDlnaRelay()
            mutableState.update { it.userStopped() }
            true
        }

    private fun activeDlnaTarget(): DlnaTarget? {
        if (activeProtocol != ActiveProtocol.Dlna) return null
        val target = targets[mutableState.value.activeDeviceId]
        if (target == null) mutableState.update { it.commandFailed("DLNA 会话已不可用") }
        return target
    }

    private suspend fun confirmDlnaTransport(
        target: DlnaTarget,
        accepted: Set<CastPlaybackStatus>,
    ): DlnaSnapshot? =
        awaitDlnaConfirmation(
            attempts = DLNA_CONFIRM_ATTEMPTS,
            delayMs = DLNA_CONFIRM_DELAY_MS,
            read = { readDlnaSnapshot(target) },
            accepted = { it.status in accepted },
        )

    private suspend fun readDlnaSnapshot(target: DlnaTarget): DlnaSnapshot {
        val transportState = queryDlnaTransportStatus(target)
        val status = dlnaStatus(transportState)
        val positionResponse =
            runCatching {
                soap(
                    target.avTransportUrl,
                    "GetPositionInfo",
                    "<InstanceID>0</InstanceID>",
                )
            }.getOrNull()
        return DlnaSnapshot(
            status = status,
            positionMs = parseDlnaTimeMillis(positionResponse?.xmlTag("RelTime")),
            durationMs = parseDlnaTimeMillis(positionResponse?.xmlTag("TrackDuration")),
            transportState = transportState.trim().uppercase().take(MAX_TRANSPORT_STATE_CHARS),
        )
    }

    private suspend fun queryDlnaTransportStatus(target: DlnaTarget): String {
        val response =
            soap(
                target.avTransportUrl,
                "GetTransportInfo",
                "<InstanceID>0</InstanceID>",
            )
        return response.xmlTag("CurrentTransportState")
            ?: error("设备未返回播放状态")
    }

    private suspend fun queryDlnaSeekCapability(target: DlnaTarget): CastCapability =
        runCatching {
            val response =
                soap(
                    target.avTransportUrl,
                    "GetCurrentTransportActions",
                    "<InstanceID>0</InstanceID>",
                )
            val actions =
                response
                    .xmlTag("Actions")
                    ?.split(',')
                    ?.map(String::trim)
                    ?.filter(String::isNotEmpty)
                    ?: return@runCatching CastCapability.Unknown
            if (actions.any { it.equals("Seek", true) }) {
                CastCapability.Supported
            } else {
                CastCapability.Unsupported
            }
        }.getOrElse {
            if (it is CancellationException) throw it
            CastCapability.Unknown
        }

    private fun markUnexpectedDisconnect(message: String) {
        dlnaPollJob?.cancel()
        dlnaPollJob = null
        detachCastClient()
        closeDlnaRelay()
        activeProtocol = null
        mutableState.update { current ->
            if (current.termination == CastTermination.UserStop) {
                current
            } else {
                current.unexpectedDisconnect(message)
            }
        }
    }

    private fun readTarget(location: String): DlnaTarget? {
        val connection = URL(location).openConnection() as HttpURLConnection
        val xml =
            try {
                connection.connectTimeout = 2_000
                connection.readTimeout = 2_000
                connection.inputStream.use {
                    readCastResponseBounded(it, MAX_DEVICE_DESCRIPTION_BYTES)
                }
            } finally {
                connection.disconnect()
            }
        val name = xml.xmlTag("friendlyName")?.xmlUnescape() ?: return null
        val avService = xml.serviceControlUrl("AVTransport") ?: return null
        val renderingService = xml.serviceControlUrl("RenderingControl")
        return DlnaTarget(
            public = CastDevice(location, name),
            avTransportUrl = URI(location).resolve(avService.xmlUnescape()).toString(),
            renderingControlUrl =
                renderingService
                    ?.let(String::xmlUnescape)
                    ?.let { URI(location).resolve(it).toString() },
        )
    }

    private suspend fun soap(
        controlUrl: String,
        action: String,
        arguments: String,
        service: String = AV_TRANSPORT_SERVICE,
    ): String =
        withContext(Dispatchers.IO) {
            val body =
                """<?xml version="1.0" encoding="utf-8"?>
            |<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
            |<s:Body><u:$action xmlns:u="$service">$arguments</u:$action></s:Body>
            |</s:Envelope>
                """.trimMargin()
            val connection = URL(controlUrl).openConnection() as HttpURLConnection
            val (status, response) =
                try {
                    connection.requestMethod = "POST"
                    connection.doOutput = true
                    connection.connectTimeout = SOAP_TIMEOUT_MS
                    connection.readTimeout = SOAP_TIMEOUT_MS
                    connection.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
                    connection.setRequestProperty("SOAPACTION", "\"$service#$action\"")
                    connection.outputStream.use { it.write(body.encodeToByteArray()) }
                    val responseCode = connection.responseCode
                    val stream =
                        if (responseCode in 200..299) {
                            connection.inputStream
                        } else {
                            connection.errorStream
                        }
                    responseCode to
                        stream
                            ?.use { readCastResponseBounded(it, MAX_SOAP_RESPONSE_BYTES) }
                            .orEmpty()
                } finally {
                    connection.disconnect()
                }
            if (status !in 200..299) throw CastHttpException(status)
            response
        }
}

internal fun readCastResponseBounded(
    input: InputStream,
    maxBytes: Int,
): String {
    require(maxBytes > 0) { "响应大小上限必须大于 0" }
    val output = ByteArrayOutputStream(minOf(maxBytes, 8 * 1024))
    val buffer = ByteArray(8 * 1024)
    var total = 0
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        if (read > maxBytes - total) {
            throw IOException("投屏设备响应超过 ${maxBytes / 1024} KiB 上限")
        }
        output.write(buffer, 0, read)
        total += read
    }
    return output.toByteArray().decodeToString()
}

private suspend fun PendingResult<out Result>.awaitSuccess(): Boolean =
    suspendCancellableCoroutine { continuation ->
        setResultCallback { result ->
            if (continuation.isActive) continuation.resume(result.status.isSuccess)
        }
        continuation.invokeOnCancellation { cancel() }
    }

private fun dlnaStatus(value: String): CastPlaybackStatus =
    when (value.trim().uppercase()) {
        "PLAYING" -> CastPlaybackStatus.Playing
        "PAUSED_PLAYBACK", "PAUSED_RECORDING" -> CastPlaybackStatus.Paused
        "TRANSITIONING" -> CastPlaybackStatus.Buffering
        "STOPPED", "NO_MEDIA_PRESENT" -> CastPlaybackStatus.Ended
        else -> CastPlaybackStatus.Error
    }

/** SOAP action names are protocol vocabulary; the viewer sees the command they asked for. */
private fun dlnaActionLabel(action: String): String =
    when (action) {
        "Play" -> "播放"
        "Pause" -> "暂停"
        "Seek" -> "跳转"
        "Stop" -> "停止"
        else -> "操作"
    }

/**
 * What a failed DLNA command tells the viewer. The socket or SOAP text said nothing they could
 * act on, and read "null" when there was none, so it goes to the diagnostic log instead.
 */
private fun dlnaNoResponse(label: String): String = "投屏设备没有响应（$label），请重试"

/** Why the viewer is back on this phone, with the one thing they could check. */
private fun dlnaStartFailureMessage(
    deviceName: String,
    failure: DlnaStartFailure,
): String =
    when (failure) {
        DlnaStartFailure.Stopped -> "「$deviceName」没有播放这个视频，可能不支持它的格式"
        DlnaStartFailure.NeverRequested -> "「$deviceName」没有来读取视频，请确认电视和手机连接同一网络"
        DlnaStartFailure.Abandoned -> "「$deviceName」读取视频后没有开始播放，可能不支持它的格式"
        DlnaStartFailure.NoProgress -> "「$deviceName」长时间没有开始播放"
    }

/** One clock for the start monitor and the relay's activity stamps. */
private fun monotonicNowMs(): Long = System.nanoTime() / 1_000_000L

private fun String.serviceControlUrl(serviceName: String): String? =
    Regex(
        """<service>.*?<serviceType>urn:schemas-upnp-org:service:$serviceName:\d+</serviceType>.*?<controlURL>(.*?)</controlURL>.*?</service>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    ).find(this)?.groupValues?.get(1)

private fun String.xmlTag(name: String): String? =
    Regex(
        """<(?:\w+:)?${Regex.escape(name)}(?:\s[^>]*)?>(.*?)</(?:\w+:)?${Regex.escape(name)}>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    ).find(this)?.groupValues?.get(1)?.trim()?.xmlUnescape()

private fun CastMediaProfile.toJson(): JSONObject =
    JSONObject().apply {
        contentType?.takeIf(String::isNotBlank)?.let { put("contentType", it) }
        videoCodec?.takeIf(String::isNotBlank)?.let { put("videoCodec", it) }
        audioCodec?.takeIf(String::isNotBlank)?.let { put("audioCodec", it) }
        width?.takeIf { it > 0 }?.let { put("width", it) }
        height?.takeIf { it > 0 }?.let { put("height", it) }
        frameRate?.takeIf { it.isFinite() && it > 0.0 }?.let { put("frameRate", it) }
        put("dolbyVision", dolbyVision)
        put("dolbyAtmos", dolbyAtmos)
    }

private fun CastMediaProfile.toCastCustomData(
    revision: Long,
    queueIndex: Int,
): JSONObject =
    JSONObject()
        .put("yfuseRevision", revision)
        .put("yfuseQueueIndex", queueIndex)
        .put("yfuseProfile", toJson())

private fun castMediaInfo(
    mediaUrl: String,
    title: String,
    profile: CastMediaProfile,
    revision: Long,
    queueIndex: Int,
): MediaInfo {
    val metadata =
        MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            putString(MediaMetadata.KEY_TITLE, title)
        }
    return MediaInfo
        .Builder(mediaUrl)
        .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
        .setContentType(profile.contentType ?: mediaUrl.contentType())
        .setMetadata(metadata)
        .setCustomData(profile.toCastCustomData(revision, queueIndex))
        .build()
}

private fun JSONObject.castCapability(name: String): CastCapability =
    when {
        !has(name) || isNull(name) -> CastCapability.Unknown
        optBoolean(name, false) -> CastCapability.Supported
        else -> CastCapability.Unsupported
    }

private const val CAST_PREFIX = "chromecast:"
private const val CAST_OUTPUT_NAMESPACE = "urn:x-cast:com.yfuse.output"
private const val CAST_COMMAND_TIMEOUT_MS = 10_000L
private const val CAST_CAPABILITY_TIMEOUT_MS = 1_500L
private const val CAST_CAPABILITY_POLL_MS = 50L
private const val CAST_PROGRESS_INTERVAL_MS = 500L
private const val DLNA_ACTIVE_POLL_INTERVAL_MS = 1_000L
private const val DLNA_IDLE_POLL_INTERVAL_MS = 5_000L
private const val DLNA_MAX_POLL_FAILURES = 3
private const val DLNA_CONFIRM_ATTEMPTS = 3
private const val DLNA_CONFIRM_DELAY_MS = 300L
private const val DLNA_SEEK_TOLERANCE_MS = 2_000L
private const val DLNA_START_SEEK_ATTEMPTS = 2
private const val DLNA_RELAY_ENDED_RELEASE_MS = 3L * 60L * 1_000L
private const val MAX_TRANSPORT_STATE_CHARS = 32
private const val SOAP_TIMEOUT_MS = 3_000
private const val MAX_DEVICE_DESCRIPTION_BYTES = 64 * 1024
private const val MAX_SOAP_RESPONSE_BYTES = 256 * 1024
private const val AV_TRANSPORT_SERVICE = "urn:schemas-upnp-org:service:AVTransport:1"
private const val RENDERING_CONTROL_SERVICE = "urn:schemas-upnp-org:service:RenderingControl:1"

private fun String.contentType(): String =
    when {
        substringBefore('?').endsWith(".m3u8", true) -> "application/x-mpegURL"
        substringBefore('?').endsWith(".webm", true) -> "video/webm"
        else -> "video/mp4"
    }

private fun String.xmlUnescape() =
    replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
