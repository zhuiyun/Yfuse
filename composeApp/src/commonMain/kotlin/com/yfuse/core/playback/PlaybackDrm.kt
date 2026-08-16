package com.yfuse.core.playback

import kotlinx.serialization.Serializable

/** DRM systems accepted by the backend-neutral player contract. */
@Serializable
enum class PlaybackDrmScheme {
    Widevine,
    ClearKey,
    PlayReady,
}

/**
 * License configuration carried to a secure platform backend without entering YCore diagnostics.
 *
 * License addresses, request headers and offline keys are credentials. They are deliberately
 * excluded from [toString] and from the capability signature used by failure/performance memory.
 */
@Serializable
data class PlaybackDrmConfiguration(
    val scheme: PlaybackDrmScheme,
    val licenseUri: String? = null,
    val requestHeaders: Map<String, String> = emptyMap(),
    val multiSession: Boolean = false,
    val forceDefaultLicenseUri: Boolean = false,
    val playClearContentWithoutKey: Boolean = true,
    val offlineKeySetId: ByteArray? = null,
) {
    init {
        require(requestHeaders.keys.none(String::isBlank)) { "DRM request header names cannot be blank" }
        require(requestHeaders.values.none(String::isBlank)) { "DRM request header values cannot be blank" }
    }

    override fun toString(): String =
        "PlaybackDrmConfiguration(" +
            "scheme=$scheme, " +
            "licenseUri=${if (licenseUri.isNullOrBlank()) "none" else "<redacted>"}, " +
            "requestHeaders=${requestHeaders.size}, " +
            "multiSession=$multiSession, " +
            "offlineKey=${offlineKeySetId != null}" +
            ")"

    override fun equals(other: Any?): Boolean =
        this === other ||
            (
                other is PlaybackDrmConfiguration &&
                    scheme == other.scheme &&
                    licenseUri == other.licenseUri &&
                    requestHeaders == other.requestHeaders &&
                    multiSession == other.multiSession &&
                    forceDefaultLicenseUri == other.forceDefaultLicenseUri &&
                    playClearContentWithoutKey == other.playClearContentWithoutKey &&
                    offlineKeySetId.contentEqualsNullable(other.offlineKeySetId)
            )

    override fun hashCode(): Int {
        var result = scheme.hashCode()
        result = 31 * result + licenseUri.hashCode()
        result = 31 * result + requestHeaders.hashCode()
        result = 31 * result + multiSession.hashCode()
        result = 31 * result + forceDefaultLicenseUri.hashCode()
        result = 31 * result + playClearContentWithoutKey.hashCode()
        result = 31 * result + (offlineKeySetId?.contentHashCode() ?: 0)
        return result
    }
}

private fun ByteArray?.contentEqualsNullable(other: ByteArray?): Boolean =
    when {
        this == null -> other == null
        other == null -> false
        else -> contentEquals(other)
    }
