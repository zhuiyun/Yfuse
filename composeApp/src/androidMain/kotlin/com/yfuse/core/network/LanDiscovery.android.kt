package com.yfuse.core.network

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class DiscoveryResponse(
    val Address: String,
    val Id: String,
    val Name: String,
)

actual fun createLanDiscovery(): LanDiscovery = AndroidLanDiscovery()

private class AndroidLanDiscovery : LanDiscovery {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun discover(timeoutMs: Long): List<DiscoveredServer> =
        withContext(Dispatchers.IO) {
            val socket = DatagramSocket().apply {
                broadcast = true
                soTimeout = 250
            }
            try {
                val message = "who is EmbyServer?".encodeToByteArray()
                val targets = buildSet {
                    add(InetAddress.getByName("255.255.255.255"))
                    NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                        .filter { it.isUp && !it.isLoopback }
                        .flatMap { it.interfaceAddresses }
                        .mapNotNullTo(this) { it.broadcast }
                }
                targets.forEach { target ->
                    runCatching {
                        socket.send(DatagramPacket(message, message.size, target, 7359))
                    }
                }
                val deadline = System.currentTimeMillis() + timeoutMs
                val found = linkedMapOf<String, DiscoveredServer>()
                val buffer = ByteArray(8 * 1024)
                while (System.currentTimeMillis() < deadline) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    val payload = packet.data.decodeToString(0, packet.length)
                    val response = runCatching {
                        json.decodeFromString(DiscoveryResponse.serializer(), payload)
                    }.getOrNull() ?: continue
                    found[response.Id] = DiscoveredServer(
                        name = response.Name,
                        address = response.Address,
                        id = response.Id,
                    )
                }
                found.values.toList()
            } finally {
                socket.close()
            }
        }
}
