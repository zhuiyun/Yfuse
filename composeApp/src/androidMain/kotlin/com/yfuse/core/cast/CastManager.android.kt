package com.yfuse.core.cast

import android.content.Context
import android.net.wifi.WifiManager
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastContext
import com.yfuse.core.logging.AppLog
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.net.URL
import java.net.SocketTimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

private lateinit var castApplicationContext: Context

fun initializeCastApplicationContext(context: Context) {
    castApplicationContext = context.applicationContext
}

actual fun createCastManager(): CastManager = AndroidCastManager(castApplicationContext)

private data class DlnaTarget(val public: CastDevice, val controlUrl: String)

private class AndroidCastManager(private val context: Context) : CastManager {
    private val mutableState = MutableStateFlow(CastState())
    override val state = mutableState.asStateFlow()
    private val targets = linkedMapOf<String, DlnaTarget>()
    private val castRoutes = linkedMapOf<String, MediaRouter.RouteInfo>()
    private val mediaRouter by lazy { MediaRouter.getInstance(context) }
    private val castSelector = MediaRouteSelector.Builder()
        .addControlCategory(
            CastMediaControlIntent.categoryForCast(
                CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID,
            ),
        )
        .build()
    private val routeCallback = object : MediaRouter.Callback() {
        override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = refreshCastRoutes()
        override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = refreshCastRoutes()
        override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = refreshCastRoutes()
    }

    override suspend fun discover() = withContext(Dispatchers.IO) {
        mutableState.value = mutableState.value.copy(discovering = true, error = null)
        val locations = linkedSetOf<String>()
        runCatching {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val multicastLock = wifi?.createMulticastLock("yfuse-dlna")?.apply {
                setReferenceCounted(false)
                acquire()
            }
            try {
                DatagramSocket().use { socket ->
                socket.soTimeout = 350
                val request = (
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
                    Regex("""(?im)^location:\s*(.+)\s*$""").find(response)
                        ?.groupValues?.get(1)?.trim()?.let(locations::add)
                }
                }
            } finally {
                multicastLock?.takeIf { it.isHeld }?.release()
            }
            var descriptionFailures = 0
            locations.forEach { location ->
                runCatching { readTarget(location) }
                    .onSuccess { target ->
                        target?.let { targets[it.public.id] = it }
                    }
                    .onFailure { error ->
                        descriptionFailures++
                        AppLog.warning(
                            category = "cast",
                            event = "device_description_failed",
                            message = "Failed to read a discovered DLNA device",
                            throwable = error,
                        )
                    }
            }
            AppLog.info(
                category = "cast",
                event = "discovery_completed",
                message = "DLNA discovery completed",
                attributes = mapOf(
                    "locationCount" to locations.size.toString(),
                    "deviceCount" to targets.size.toString(),
                    "descriptionFailures" to descriptionFailures.toString(),
                ),
            )
        }.onFailure { error ->
            AppLog.warning(
                category = "cast",
                event = "discovery_failed",
                message = "DLNA discovery failed",
                throwable = error,
            )
            mutableState.value = mutableState.value.copy(error = "未发现可用的 DLNA 投屏设备")
        }
        withContext(Dispatchers.Main.immediate) {
            mediaRouter.removeCallback(routeCallback)
            mediaRouter.addCallback(
                castSelector,
                routeCallback,
                MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY,
            )
            refreshCastRoutes()
        }
        mutableState.value = mutableState.value.copy(
            discovering = false,
            devices = allDevices(),
            error = if (targets.isEmpty() && castRoutes.isEmpty()) "未发现可用的投屏设备" else null,
        )
    }

    override suspend fun play(deviceId: String, mediaUrl: String, title: String) =
        if (deviceId.startsWith(CAST_PREFIX)) {
            playChromecast(deviceId, mediaUrl, title)
        } else withContext(Dispatchers.IO) {
            val target = targets[deviceId] ?: run {
                AppLog.warning(
                    category = "cast",
                    event = "target_missing",
                    message = "Selected DLNA device is no longer available",
                )
                return@withContext
            }
            runCatching {
                soap(
                    target.controlUrl,
                    "SetAVTransportURI",
                    "<InstanceID>0</InstanceID>" +
                        "<CurrentURI>${mediaUrl.xmlEscape()}</CurrentURI>" +
                        "<CurrentURIMetaData>${dlnaMetadata(mediaUrl, title).xmlEscape()}</CurrentURIMetaData>",
                )
                soap(
                    target.controlUrl,
                    "Play",
                    "<InstanceID>0</InstanceID><Speed>1</Speed>",
                )
            }.onSuccess {
                AppLog.info(
                    category = "cast",
                    event = "play_started",
                    message = "DLNA playback started",
                )
                mutableState.value = mutableState.value.copy(activeDeviceId = deviceId, error = null)
            }.onFailure { error ->
                AppLog.error(
                    category = "cast",
                    event = "play_failed",
                    message = "DLNA playback request failed",
                    throwable = error,
                )
                mutableState.value = mutableState.value.copy(
                    error = "投屏失败：${error.message ?: "设备拒绝播放"}",
                )
            }
        }

    override suspend fun stop() = withContext(Dispatchers.IO) {
        if (mutableState.value.activeDeviceId?.startsWith(CAST_PREFIX) == true) {
            withContext(Dispatchers.Main.immediate) {
                runCatching {
                    val castContext = CastContext.getSharedInstance(context)
                    castContext.sessionManager.currentCastSession?.remoteMediaClient?.stop()
                    castContext.sessionManager.endCurrentSession(true)
                }
                mutableState.value = mutableState.value.copy(activeDeviceId = null)
            }
            return@withContext
        }
        val target = targets[mutableState.value.activeDeviceId] ?: return@withContext
        runCatching {
            soap(target.controlUrl, "Stop", "<InstanceID>0</InstanceID>")
        }.onSuccess {
            AppLog.info(
                category = "cast",
                event = "playback_stopped",
                message = "DLNA playback stopped",
            )
        }.onFailure { error ->
            AppLog.warning(
                category = "cast",
                event = "stop_failed",
                message = "Failed to stop DLNA playback",
                throwable = error,
            )
        }
        mutableState.value = mutableState.value.copy(activeDeviceId = null)
    }

    private fun refreshCastRoutes() {
        castRoutes.clear()
        mediaRouter.routes
            .filter { !it.isDefault && it.matchesSelector(castSelector) }
            .forEach { castRoutes[CAST_PREFIX + it.id] = it }
        mutableState.value = mutableState.value.copy(devices = allDevices())
    }

    private fun allDevices(): List<CastDevice> =
        targets.values.map(DlnaTarget::public) + castRoutes.map { (id, route) ->
            CastDevice(id, "${route.name} · Chromecast")
        }

    private suspend fun playChromecast(deviceId: String, mediaUrl: String, title: String) {
        val route = castRoutes[deviceId] ?: return
        withContext(Dispatchers.Main.immediate) {
            runCatching {
                mediaRouter.selectRoute(route)
                val castContext = CastContext.getSharedInstance(context)
                var client = castContext.sessionManager.currentCastSession?.remoteMediaClient
                var attempts = 0
                while (client == null && attempts < 20) {
                    delay(250L)
                    client = castContext.sessionManager.currentCastSession?.remoteMediaClient
                    attempts++
                }
                val remote = requireNotNull(client) { "Chromecast 会话建立超时" }
                val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
                    putString(MediaMetadata.KEY_TITLE, title)
                }
                val info = MediaInfo.Builder(mediaUrl)
                    .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
                    .setContentType(mediaUrl.contentType())
                    .setMetadata(metadata)
                    .build()
                remote.load(
                    MediaLoadRequestData.Builder()
                        .setMediaInfo(info)
                        .setAutoplay(true)
                        .build(),
                )
            }.onSuccess {
                mutableState.value = mutableState.value.copy(activeDeviceId = deviceId, error = null)
            }.onFailure { error ->
                AppLog.error("cast", "chromecast_play_failed", "Chromecast playback failed", error)
                mutableState.value = mutableState.value.copy(
                    error = "Chromecast 投屏失败：${error.message ?: "连接失败"}",
                )
            }
        }
    }

    private fun readTarget(location: String): DlnaTarget? {
        val connection = URL(location).openConnection() as HttpURLConnection
        connection.connectTimeout = 2_000
        connection.readTimeout = 2_000
        val xml = connection.inputStream.bufferedReader().use { it.readText() }
        val name = Regex("""<friendlyName>(.*?)</friendlyName>""", RegexOption.IGNORE_CASE)
            .find(xml)?.groupValues?.get(1)?.xmlUnescape() ?: return null
        val service = Regex(
            """<service>.*?<serviceType>urn:schemas-upnp-org:service:AVTransport:\d+</serviceType>.*?<controlURL>(.*?)</controlURL>.*?</service>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        ).find(xml)?.groupValues?.get(1)?.xmlUnescape() ?: return null
        val control = URI(location).resolve(service).toString()
        return DlnaTarget(CastDevice(location, name), control)
    }

    private fun soap(controlUrl: String, action: String, arguments: String) {
        val body = """<?xml version="1.0" encoding="utf-8"?>
            |<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
            |<s:Body><u:$action xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">$arguments</u:$action></s:Body>
            |</s:Envelope>""".trimMargin()
        val connection = URL(controlUrl).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = 3_000
        connection.readTimeout = 3_000
        connection.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
        connection.setRequestProperty(
            "SOAPACTION",
            "\"urn:schemas-upnp-org:service:AVTransport:1#$action\"",
        )
        connection.outputStream.use { it.write(body.encodeToByteArray()) }
        require(connection.responseCode in 200..299) { "HTTP ${connection.responseCode}" }
        connection.inputStream.close()
    }
}

private const val CAST_PREFIX = "chromecast:"

private fun String.contentType(): String = when {
    substringBefore('?').endsWith(".m3u8", true) -> "application/x-mpegURL"
    substringBefore('?').endsWith(".webm", true) -> "video/webm"
    else -> "video/mp4"
}

private fun dlnaMetadata(url: String, title: String): String =
    """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"><item id="0" parentID="0" restricted="1"><dc:title>${title.xmlEscape()}</dc:title><upnp:class>object.item.videoItem</upnp:class><res protocolInfo="http-get:*:${url.contentType()}:*">${url.xmlEscape()}</res></item></DIDL-Lite>"""

private fun String.xmlEscape() = replace("&", "&amp;")
    .replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

private fun String.xmlUnescape() = replace("&amp;", "&")
    .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
