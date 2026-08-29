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
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.api.PendingResult
import com.google.android.gms.common.api.Result
import com.yfuse.core.logging.AppLog
import com.yfuse.core.network.LocalNetworkPermissionRequiredException
import com.yfuse.core.network.requireLocalNetworkPermission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
import org.json.JSONObject

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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var dlnaPollJob: Job? = null
    private var activeProtocol: ActiveProtocol? = null
    private var castClient: RemoteMediaClient? = null
    private var castMessageSession: CastSession? = null
    private var sessionListenerRegistered = false
    private var suppressNextSessionEnd = false

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
        withContext(Dispatchers.IO) {
            mutableState.update { it.copy(discovering = true, error = null) }
            val locations = linkedSetOf<String>()
            var discoveryError: Throwable? = null
            runCatching {
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
                    runCatching { readTarget(location) }
                        .onSuccess { target -> target?.let { discoveredTargets[it.public.id] = it } }
                        .onFailure { error ->
                            AppLog.warning(
                                category = "cast",
                                event = "device_description_failed",
                                message = "Failed to read a discovered DLNA device",
                                throwable = error,
                            )
                        }
                }
                targets.clear()
                targets.putAll(discoveredTargets)
            }.onFailure { error ->
                discoveryError = error
                AppLog.warning("cast", "discovery_failed", "DLNA discovery failed", error)
            }

            withContext(Dispatchers.Main.immediate) {
                ensureCastCallbacks()
                mediaRouter.removeCallback(routeCallback)
                mediaRouter.addCallback(
                    castSelector,
                    routeCallback,
                    MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY,
                )
                refreshCastRoutes()
            }
            val devices = allDevices()
            mutableState.update { current ->
                current.copy(
                    discovering = false,
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
        }

    override suspend fun play(
        deviceId: String,
        mediaUrl: String,
        title: String,
        positionMs: Long,
        fallbackMediaUrl: String?,
        mediaProfile: CastMediaProfile,
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
            )
        } else {
            playDlna(
                deviceId,
                usableFallback ?: resolvedMediaUrl,
                title,
                positionMs,
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

    override suspend fun stop(): Boolean =
        when (activeProtocol) {
            ActiveProtocol.Chromecast -> stopChromecast()
            ActiveProtocol.Dlna -> stopDlna()
            null -> {
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
                val activeSession = requireNotNull(session) { "Chromecast 会话建立超时" }
                val remote = requireNotNull(activeSession.remoteMediaClient) { "Chromecast 媒体通道不可用" }
                attachCastClient(activeSession)

                val revision = mutableState.value.sessionRevision
                requestReceiverCapabilities(activeSession, revision, mediaProfile)
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

                val metadata =
                    MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
                        putString(MediaMetadata.KEY_TITLE, title)
                    }
                val info =
                    MediaInfo
                        .Builder(selectedUrl)
                        .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
                        .setContentType(selectedProfile.contentType ?: selectedUrl.contentType())
                        .setMetadata(metadata)
                        .setCustomData(selectedProfile.toCastCustomData(revision))
                        .build()
                mutableState.update { it.copy(status = CastPlaybackStatus.Buffering) }
                val accepted =
                    withTimeoutOrNull(CAST_COMMAND_TIMEOUT_MS) {
                        remote
                            .load(
                                MediaLoadRequestData
                                    .Builder()
                                    .setMediaInfo(info)
                                    .setAutoplay(true)
                                    .setCurrentTime(positionMs.coerceAtLeast(0L))
                                    .build(),
                            ).awaitSuccess()
                    } == true
                check(accepted) { "接收端拒绝加载媒体" }
                activeProtocol = ActiveProtocol.Chromecast
                dlnaPollJob?.cancel()
                mutableState.update { it.remoteUpdate(CastPlaybackStatus.Buffering) }
                remote.requestStatus()
                syncChromecastStatus()
                true
            }.getOrElse { error ->
                AppLog.error("cast", "chromecast_play_failed", "Chromecast playback failed", error)
                activeProtocol = previousProtocol
                if (previousProtocol == ActiveProtocol.Dlna) {
                    detachCastClient()
                    previousDlnaTarget?.let(::startDlnaPolling)
                } else if (previousProtocol == ActiveProtocol.Chromecast) {
                    CastContext
                        .getSharedInstance(context)
                        .sessionManager.currentCastSession
                        ?.let(::attachCastClient)
                } else {
                    detachCastClient()
                }
                mutableState.value =
                    if (previous.hasActiveSession) {
                        previous.copy(error = "Chromecast 投屏失败：${error.message ?: "连接失败"}")
                    } else {
                        previous
                            .connectingTo(device, positionMs)
                            .commandFailed("Chromecast 投屏失败：${error.message ?: "连接失败"}")
                    }
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
            )
        if (playbackStatus == CastPlaybackStatus.Error) {
            mutableState.update { it.commandFailed("Chromecast 接收端报告播放错误") }
            return
        }
        mutableState.update {
            it.remoteUpdate(
                status = playbackStatus,
                positionMs = remote.approximateStreamPosition,
                durationMs = remote.streamDuration,
                volume = mediaStatus.streamVolume.toFloat(),
                capabilities = capabilities,
            )
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
    ): Boolean =
        withContext(Dispatchers.IO) {
            val target = targets[deviceId]
            if (target == null) {
                mutableState.update { it.commandFailed("DLNA 设备已离线，请重新发现") }
                return@withContext false
            }
            val previous = mutableState.value
            val previousProtocol = activeProtocol
            val previousDlnaTarget = previous.activeDeviceId?.let(targets::get)
            if (previousProtocol == ActiveProtocol.Dlna) {
                dlnaPollJob?.cancel()
                dlnaPollJob = null
            }
            mutableState.value = previous.connectingTo(target.public, positionMs)
            runCatching {
                soap(
                    target.avTransportUrl,
                    "SetAVTransportURI",
                    "<InstanceID>0</InstanceID>" +
                        "<CurrentURI>${mediaUrl.xmlEscape()}</CurrentURI>" +
                        "<CurrentURIMetaData>${dlnaMetadata(mediaUrl, title).xmlEscape()}</CurrentURIMetaData>",
                )

                val seekCapability = queryDlnaSeekCapability(target)
                if (positionMs > 0L && seekCapability == CastCapability.Supported) {
                    runCatching {
                        soap(
                            target.avTransportUrl,
                            "Seek",
                            "<InstanceID>0</InstanceID><Unit>REL_TIME</Unit>" +
                                "<Target>${formatDlnaTime(positionMs)}</Target>",
                        )
                    }.onFailure { error ->
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
                activeProtocol = ActiveProtocol.Dlna
                detachCastClient()
                mutableState.update {
                    it.remoteUpdate(
                        status = confirmed.status,
                        positionMs = confirmed.positionMs,
                        durationMs = confirmed.durationMs,
                        capabilities = capabilities,
                    )
                }
                startDlnaPolling(target)
                true
            }.getOrElse { error ->
                AppLog.error("cast", "dlna_play_failed", "DLNA playback request failed", error)
                activeProtocol = previousProtocol
                if (previousProtocol == ActiveProtocol.Dlna) {
                    previousDlnaTarget?.let(::startDlnaPolling)
                }
                mutableState.value =
                    if (previous.hasActiveSession) {
                        previous.copy(error = "DLNA 投屏失败：${error.message ?: "设备拒绝播放"}")
                    } else {
                        previous
                            .connectingTo(target.public, positionMs)
                            .commandFailed("DLNA 投屏失败：${error.message ?: "设备拒绝播放"}")
                    }
                false
            }
        }

    private fun startDlnaPolling(target: DlnaTarget) {
        dlnaPollJob?.cancel()
        dlnaPollJob =
            scope.launch {
                var failures = 0
                while (
                    isActive &&
                    activeProtocol == ActiveProtocol.Dlna &&
                    mutableState.value.activeDeviceId == target.public.id
                ) {
                    delay(
                        if (mutableState.value.status == CastPlaybackStatus.Playing) {
                            DLNA_ACTIVE_POLL_INTERVAL_MS
                        } else {
                            DLNA_IDLE_POLL_INTERVAL_MS
                        },
                    )
                    runCatching { readDlnaSnapshot(target) }
                        .onSuccess { snapshot ->
                            failures = 0
                            if (snapshot.status == CastPlaybackStatus.Error) {
                                mutableState.update { it.commandFailed("DLNA 接收端报告未知播放状态") }
                                return@launch
                            }
                            mutableState.update {
                                it.remoteUpdate(
                                    status = snapshot.status,
                                    positionMs = snapshot.positionMs,
                                    durationMs = snapshot.durationMs,
                                )
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

    private suspend fun dlnaTransportCommand(
        action: String,
        arguments: String,
        accepted: Set<CastPlaybackStatus>,
    ): Boolean =
        withContext(Dispatchers.IO) {
            val target = activeDlnaTarget() ?: return@withContext false
            runCatching {
                soap(target.avTransportUrl, action, arguments)
                confirmDlnaTransport(target, accepted) ?: error("设备未确认$action")
            }.onSuccess { snapshot ->
                mutableState.update {
                    it.remoteUpdate(
                        status = snapshot.status,
                        positionMs = snapshot.positionMs,
                        durationMs = snapshot.durationMs,
                    )
                }
            }.onFailure { error ->
                mutableState.update { it.commandFailed("DLNA $action 失败：${error.message}") }
            }.isSuccess
        }

    private suspend fun seekDlna(positionMs: Long): Boolean =
        withContext(Dispatchers.IO) {
            val target = activeDlnaTarget() ?: return@withContext false
            val capability =
                when (mutableState.value.capabilities.seek) {
                    CastCapability.Unknown -> queryDlnaSeekCapability(target)
                    else -> mutableState.value.capabilities.seek
                }
            mutableState.update { it.copy(capabilities = it.capabilities.copy(seek = capability)) }
            if (capability != CastCapability.Supported) {
                mutableState.update { it.copy(error = "此 DLNA 设备未确认支持跳转") }
                return@withContext false
            }
            val targetPosition = positionMs.coerceAtLeast(0L)
            val previousStatus = mutableState.value.status
            runCatching {
                soap(
                    target.avTransportUrl,
                    "Seek",
                    "<InstanceID>0</InstanceID><Unit>REL_TIME</Unit>" +
                        "<Target>${formatDlnaTime(targetPosition)}</Target>",
                )
                var confirmed: DlnaSnapshot? = null
                repeat(DLNA_CONFIRM_ATTEMPTS) {
                    val snapshot = readDlnaSnapshot(target)
                    val actual = snapshot.positionMs
                    if (actual != null && kotlin.math.abs(actual - targetPosition) <= DLNA_SEEK_TOLERANCE_MS) {
                        confirmed = snapshot
                        return@repeat
                    }
                    delay(DLNA_CONFIRM_DELAY_MS)
                }
                confirmed ?: error("设备未确认跳转位置")
            }.onSuccess { snapshot ->
                mutableState.update {
                    it.remoteUpdate(
                        status =
                            snapshot.status.takeUnless { value -> value == CastPlaybackStatus.Error }
                                ?: previousStatus,
                        positionMs = snapshot.positionMs,
                        durationMs = snapshot.durationMs,
                    )
                }
            }.onFailure { error ->
                mutableState.update { it.commandFailed("DLNA 跳转失败：${error.message}") }
            }.isSuccess
        }

    private suspend fun setDlnaVolume(volume: Float): Boolean =
        withContext(Dispatchers.IO) {
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
            runCatching {
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
            }.onSuccess { confirmed ->
                mutableState.update {
                    it.remoteUpdate(
                        status = it.status,
                        volume = (confirmed / 100f).coerceIn(0f, 1f),
                        capabilities = it.capabilities.copy(volume = CastCapability.Supported),
                    )
                }
            }.onFailure { error ->
                mutableState.update {
                    it.copy(
                        capabilities = it.capabilities.copy(volume = CastCapability.Unknown),
                        error = "DLNA 音量调节失败：${error.message}",
                    )
                }
            }.isSuccess
        }

    private suspend fun stopDlna(): Boolean =
        withContext(Dispatchers.IO) {
            val target = activeDlnaTarget() ?: return@withContext false
            val stopped =
                runCatching {
                    soap(target.avTransportUrl, "Stop", "<InstanceID>0</InstanceID>")
                    repeat(DLNA_CONFIRM_ATTEMPTS) {
                        val transport = queryDlnaTransportStatus(target)
                        if (transport == "STOPPED" || transport == "NO_MEDIA_PRESENT") return@runCatching true
                        delay(DLNA_CONFIRM_DELAY_MS)
                    }
                    false
                }.getOrElse { error ->
                    mutableState.update { it.commandFailed("DLNA 停止失败：${error.message}") }
                    false
                }
            if (!stopped) {
                mutableState.update { it.commandFailed("DLNA 设备未确认停止") }
                return@withContext false
            }
            dlnaPollJob?.cancel()
            dlnaPollJob = null
            activeProtocol = null
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
    ): DlnaSnapshot? {
        repeat(DLNA_CONFIRM_ATTEMPTS) {
            val snapshot = readDlnaSnapshot(target)
            if (snapshot.status in accepted) return snapshot
            delay(DLNA_CONFIRM_DELAY_MS)
        }
        return null
    }

    private fun readDlnaSnapshot(target: DlnaTarget): DlnaSnapshot {
        val status = dlnaStatus(queryDlnaTransportStatus(target))
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
        )
    }

    private fun queryDlnaTransportStatus(target: DlnaTarget): String {
        val response =
            soap(
                target.avTransportUrl,
                "GetTransportInfo",
                "<InstanceID>0</InstanceID>",
            )
        return response.xmlTag("CurrentTransportState")
            ?: error("设备未返回播放状态")
    }

    private fun queryDlnaSeekCapability(target: DlnaTarget): CastCapability =
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
        }.getOrElse { CastCapability.Unknown }

    private fun markUnexpectedDisconnect(message: String) {
        dlnaPollJob?.cancel()
        dlnaPollJob = null
        detachCastClient()
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

    private fun soap(
        controlUrl: String,
        action: String,
        arguments: String,
        service: String = AV_TRANSPORT_SERVICE,
    ): String {
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
        return response
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

private fun CastMediaProfile.toCastCustomData(revision: Long): JSONObject =
    JSONObject()
        .put("yfuseRevision", revision)
        .put("yfuseProfile", toJson())

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

private fun dlnaMetadata(
    url: String,
    title: String,
): String =
    """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"><item id="0" parentID="0" restricted="1"><dc:title>${title.xmlEscape()}</dc:title><upnp:class>object.item.videoItem</upnp:class><res protocolInfo="http-get:*:${url.contentType()}:*">${url.xmlEscape()}</res></item></DIDL-Lite>"""

private fun String.xmlEscape() =
    replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

private fun String.xmlUnescape() =
    replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
