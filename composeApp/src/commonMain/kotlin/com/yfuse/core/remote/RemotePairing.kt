package com.yfuse.core.remote

import com.yfuse.watch.protocol.WatchProtocol

/**
 * Stands for every phone on a relay without [WatchProtocol.CAPABILITY_REMOTE_PAIRING], which never
 * says which phone is which. It is never one a television may remember.
 */
internal const val UNNAMED_REMOTE_PHONE = WatchProtocol.REMOTE_EPHEMERAL_DEVICE_PREFIX + "phone"

/**
 * Which phones are on this television's 手机遥控 and which of them it has let in. Nothing a phone
 * sends counts until then. Each step returns the next state, so the host applies the relay's news
 * and the viewer's answers with `MutableStateFlow.update`, from whichever thread they come.
 */
internal data class RemotePairing(
    val phones: List<RemoteControlPhone> = emptyList(),
    /**
     * Phones let in with 允许一次. It holds until this television stops hosting, so a phone whose
     * network blinks is not asked about again; letting one go ends it at once.
     */
    val allowedOnce: Set<String> = emptySet(),
) {
    /** A phone connected; [deviceId] is null on a relay that does not name phones. */
    fun connected(
        deviceId: String?,
        name: String?,
        trusted: (String) -> Boolean,
    ): RemotePairing {
        if (deviceId == null) {
            // A newcomer's keys could not be told from those of a phone already let in, so the
            // television asks again before any of them count.
            val others = phones.filterNot { it.deviceId == UNNAMED_REMOTE_PHONE }
            return copy(phones = others + RemoteControlPhone(UNNAMED_REMOTE_PHONE, name = null, allowed = false))
        }
        if (phones.none { it.deviceId == deviceId }) {
            return copy(phones = phones + RemoteControlPhone(deviceId, name, allowed = lets(deviceId, trusted)))
        }
        return copy(phones = phones.map { if (it.deviceId == deviceId) it.copy(name = name ?: it.name) else it })
    }

    /**
     * A key or text came from [deviceId]. One this television has not heard connect — it hosted
     * again while the phone stayed on — is asked about like a newcomer.
     */
    fun heard(
        deviceId: String?,
        trusted: (String) -> Boolean,
    ): RemotePairing {
        val id = deviceId ?: UNNAMED_REMOTE_PHONE
        if (phones.any { it.deviceId == id }) return this
        val allowed = deviceId != null && lets(deviceId, trusted)
        return copy(phones = phones + RemoteControlPhone(id, name = null, allowed = allowed))
    }

    /** Whether what [deviceId] sends reaches the television. */
    fun admits(deviceId: String?): Boolean {
        val id = deviceId ?: UNNAMED_REMOTE_PHONE
        return phones.any { it.deviceId == id && it.allowed }
    }

    /** [deviceId] left, [remaining] phones are still on; a relay that names none says only how many. */
    fun disconnected(
        deviceId: String?,
        remaining: Int?,
    ): RemotePairing =
        when {
            remaining == 0 -> copy(phones = emptyList())
            deviceId == null -> this
            else -> copy(phones = phones.filterNot { it.deviceId == deviceId })
        }

    /** The relay took this television's session with [count] phones still on it: none means start over. */
    fun hosted(count: Int): RemotePairing = if (count == 0) copy(phones = emptyList()) else this

    /** 允许一次, or the moment after 始终允许 is remembered. */
    fun allow(deviceId: String): RemotePairing =
        copy(
            phones = phones.map { if (it.deviceId == deviceId) it.copy(allowed = true) else it },
            // Unnamed phones cannot be told apart, so each newcomer among them is asked about.
            allowedOnce = if (deviceId == UNNAMED_REMOTE_PHONE) allowedOnce else allowedOnce + deviceId,
        )

    /** 拒绝 or 断开: the phone is gone from this television until it connects and is let in again. */
    fun release(deviceId: String): RemotePairing =
        copy(
            phones = phones.filterNot { it.deviceId == deviceId },
            allowedOnce = allowedOnce - deviceId,
        )

    /**
     * The phones let in on the way here from [previous]: by the viewer, or at once as they came —
     * trusted, or back while 允许一次 still held. These are the ones the relay is to tell.
     */
    fun admittedSince(previous: RemotePairing): List<String> =
        phones
            .filter { phone -> phone.allowed && previous.phones.none { it.deviceId == phone.deviceId && it.allowed } }
            .map { it.deviceId }

    private fun lets(
        deviceId: String,
        trusted: (String) -> Boolean,
    ): Boolean = deviceId in allowedOnce || (WatchProtocol.isStableRemoteDeviceId(deviceId) && trusted(deviceId))
}
