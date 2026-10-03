package com.yfuse.tv.remote

import com.russhwolf.settings.Settings
import com.yfuse.watch.protocol.WatchProtocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** A phone let in with 始终允许此设备; it is not asked about again until it is removed. */
@Serializable
internal data class TrustedPhone(
    val deviceId: String,
    val name: String? = null,
)

/**
 * The television's own 手机遥控 choices, kept on this device alone: whether phones may control it
 * at all, and which phones it lets in without asking. Neither syncs with the account — trusting a
 * phone is a decision about this room, not about everywhere the account is signed in.
 */
internal class TvPhoneRemotePreferences(
    private val settings: Settings,
) {
    private val _enabled = MutableStateFlow(settings.getBoolean(ENABLED_KEY, true))

    /** On unless the viewer turned it off: off, this television does not offer 手机遥控 at all. */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _trusted = MutableStateFlow(decodeTrustedPhones(settings.getStringOrNull(TRUSTED_KEY)))

    /** Newest first. */
    val trusted: StateFlow<List<TrustedPhone>> = _trusted.asStateFlow()

    fun setEnabled(value: Boolean) {
        settings.putBoolean(ENABLED_KEY, value)
        _enabled.value = value
    }

    /** Read as each phone connects, off the main thread. */
    fun isTrusted(deviceId: String): Boolean = _trusted.value.any { it.deviceId == deviceId }

    fun trust(
        deviceId: String,
        name: String?,
    ) {
        save(_trusted.value.trusting(TrustedPhone(deviceId, name)))
    }

    fun forget(deviceId: String) {
        save(_trusted.value.filterNot { it.deviceId == deviceId })
    }

    private fun save(phones: List<TrustedPhone>) {
        settings.putString(TRUSTED_KEY, encodeTrustedPhones(phones))
        _trusted.value = phones
    }

    private companion object {
        const val ENABLED_KEY = "tv.phoneRemote.enabled"
        const val TRUSTED_KEY = "tv.phoneRemote.trusted.v1"
    }
}

/** A household's phones; trusting one more lets the longest-trusted go. */
internal const val MAX_TRUSTED_PHONES = 8

private val trustedPhonesJson = Json { ignoreUnknownKeys = true }

/** [phone] first, once, with its latest name, and no more than [MAX_TRUSTED_PHONES] in all. */
internal fun List<TrustedPhone>.trusting(phone: TrustedPhone): List<TrustedPhone> =
    (listOf(phone) + filterNot { it.deviceId == phone.deviceId }).take(MAX_TRUSTED_PHONES)

internal fun encodeTrustedPhones(phones: List<TrustedPhone>): String =
    trustedPhonesJson.encodeToString(ListSerializer(TrustedPhone.serializer()), phones)

/**
 * What was saved, keeping only ids a phone could still name itself with: a stored value that does
 * not read is treated as no trusted phone at all, never as a reason to let one in.
 */
internal fun decodeTrustedPhones(raw: String?): List<TrustedPhone> {
    val saved =
        raw
            ?.let { runCatching { trustedPhonesJson.decodeFromString(ListSerializer(TrustedPhone.serializer()), it) } }
            ?.getOrNull()
            .orEmpty()
    return saved
        .filter { WatchProtocol.isStableRemoteDeviceId(it.deviceId) }
        .distinctBy { it.deviceId }
        .take(MAX_TRUSTED_PHONES)
}
