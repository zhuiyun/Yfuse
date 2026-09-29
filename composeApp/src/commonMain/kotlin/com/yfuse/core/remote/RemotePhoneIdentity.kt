package com.yfuse.core.remote

import com.yfuse.core.security.platformCryptoPrimitives
import com.yfuse.deviceId
import com.yfuse.deviceModel
import com.yfuse.watch.protocol.WatchProtocol

/**
 * How this phone names itself to a television on 手机遥控, so the television can ask about it by
 * name before it presses anything and, once told to, let it in for good — see
 * [WatchProtocol.CAPABILITY_REMOTE_PAIRING].
 */
data class RemotePhoneIdentity(
    val deviceId: String,
    val name: String?,
)

/** This install, as a television will know it. */
internal fun localRemotePhoneIdentity(): RemotePhoneIdentity =
    RemotePhoneIdentity(deviceId = remoteDeviceIdOf(deviceId()), name = remoteDeviceName(deviceModel()))

/**
 * The id a television remembers a phone by: the install's own, hashed for this one purpose, so the
 * relay and the television learn which phone it is and never the id this app gives media servers.
 */
internal fun remoteDeviceIdOf(installId: String): String =
    platformCryptoPrimitives()
        .sha256("yfuse.remote-control:$installId".encodeToByteArray())
        .take(REMOTE_DEVICE_ID_BYTES)
        .joinToString("") { byte -> (byte.toInt() and 0xFF).toString(16).padStart(2, '0') }

/**
 * The model as a name the relay will pass on: no control characters, one space between words, and
 * no longer than a name may be. Null when nothing of it is left.
 */
internal fun remoteDeviceName(model: String): String? =
    model
        .split(WHITESPACE)
        .map { word -> word.filterNot(Char::isISOControl) }
        .filter(String::isNotEmpty)
        .joinToString(" ")
        .take(WatchProtocol.MAX_NAME_GRAPHEMES)
        .trim()
        .takeIf { it.isNotEmpty() && WatchProtocol.isValidOptionalName(it) }

private const val REMOTE_DEVICE_ID_BYTES = 16
private val WHITESPACE = Regex("\\s+")
