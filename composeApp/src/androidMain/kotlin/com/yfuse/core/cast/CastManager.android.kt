package com.yfuse.core.cast

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.net.URL
import java.net.SocketTimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

actual fun createCastManager(): CastManager = DlnaCastManager()

private data class DlnaTarget(val public: CastDevice, val controlUrl: String)

private class DlnaCastManager : CastManager {
    private val mutableState = MutableStateFlow(CastState())
    override val state = mutableState.asStateFlow()
    private val targets = linkedMapOf<String, DlnaTarget>()

    override suspend fun discover() = withContext(Dispatchers.IO) {
        mutableState.value = mutableState.value.copy(discovering = true, error = null)
        val locations = linkedSetOf<String>()
        runCatching {
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
            locations.forEach { location ->
                readTarget(location)?.let { targets[it.public.id] = it }
            }
        }.onFailure {
            mutableState.value = mutableState.value.copy(error = "未发现可用的 DLNA 投屏设备")
        }
        mutableState.value = mutableState.value.copy(
            discovering = false,
            devices = targets.values.map(DlnaTarget::public),
            error = if (targets.isEmpty()) "未发现可用的 DLNA 投屏设备" else null,
        )
    }

    override suspend fun play(deviceId: String, mediaUrl: String, title: String) =
        withContext(Dispatchers.IO) {
            val target = targets[deviceId] ?: return@withContext
            runCatching {
                soap(
                    target.controlUrl,
                    "SetAVTransportURI",
                    "<InstanceID>0</InstanceID>" +
                        "<CurrentURI>${mediaUrl.xmlEscape()}</CurrentURI>" +
                        "<CurrentURIMetaData></CurrentURIMetaData>",
                )
                soap(
                    target.controlUrl,
                    "Play",
                    "<InstanceID>0</InstanceID><Speed>1</Speed>",
                )
            }.onSuccess {
                mutableState.value = mutableState.value.copy(activeDeviceId = deviceId, error = null)
            }.onFailure {
                mutableState.value = mutableState.value.copy(error = "投屏失败：${it.message ?: "设备拒绝播放"}")
            }
        }

    override suspend fun stop() = withContext(Dispatchers.IO) {
        val target = targets[mutableState.value.activeDeviceId] ?: return@withContext
        runCatching {
            soap(target.controlUrl, "Stop", "<InstanceID>0</InstanceID>")
        }
        mutableState.value = mutableState.value.copy(activeDeviceId = null)
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

private fun String.xmlEscape() = replace("&", "&amp;")
    .replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

private fun String.xmlUnescape() = replace("&amp;", "&")
    .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
