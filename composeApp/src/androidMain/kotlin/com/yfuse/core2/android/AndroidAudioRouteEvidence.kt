package com.yfuse.core2.android

import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioTrack
import com.yfuse.core2.api.YDolbyAtmosOutputMode
import com.yfuse.core2.capability.YAudioCodec

/** Active AudioTrack route facts. Product names are bounded and control characters are removed. */
internal data class AndroidAudioRouteEvidence(
    val label: String = "",
    val verified: Boolean = false,
    val encodings: Set<Int> = emptySet(),
)

/** Vendor/HDMI evidence hook; Android's TrueHD encoding bit alone cannot prove Atmos objects. */
internal fun interface AndroidTrueHdAtmosEvidenceProvider {
    fun isTrueHdAtmosOutputVerified(
        sourceCodec: YAudioCodec,
        sinkCodec: YAudioCodec?,
        route: AndroidAudioRouteEvidence,
    ): Boolean
}

internal object FailClosedAndroidTrueHdAtmosEvidenceProvider : AndroidTrueHdAtmosEvidenceProvider {
    override fun isTrueHdAtmosOutputVerified(
        sourceCodec: YAudioCodec,
        sinkCodec: YAudioCodec?,
        route: AndroidAudioRouteEvidence,
    ): Boolean = false
}

/** Requires clock progress on the current route before output is treated as physically observed. */
internal class AndroidRoutedOutputProgress(
    private val staleAfterNs: Long = DEFAULT_ROUTED_OUTPUT_STALE_AFTER_NS,
    private val nowNs: () -> Long = System::nanoTime,
) {
    private var routeGeneration: Long? = null
    private var lastPositionUs: Long? = null
    private var lastProgressNs = 0L
    private var proven = false

    init {
        require(staleAfterNs > 0L)
    }

    fun observe(
        currentRouteGeneration: Long,
        clock: YAudioClockSnapshot?,
        playing: Boolean,
    ): Boolean {
        val now = nowNs()
        if (routeGeneration != currentRouteGeneration) {
            routeGeneration = currentRouteGeneration
            lastPositionUs = clock?.positionUs
            lastProgressNs = now
            proven = false
            return false
        }
        if (!playing || clock == null) return false
        val previousPositionUs = lastPositionUs
        if (previousPositionUs == null || clock.positionUs < previousPositionUs) {
            lastPositionUs = clock.positionUs
            lastProgressNs = now
            proven = false
            return false
        }
        if (clock.positionUs > previousPositionUs) {
            lastPositionUs = clock.positionUs
            lastProgressNs = now
            proven = true
        }
        return proven && now - lastProgressNs <= staleAfterNs
    }

    fun reset() {
        routeGeneration = null
        lastPositionUs = null
        lastProgressNs = 0L
        proven = false
    }
}

internal fun AudioTrack.activeRouteEvidence(clockAdvancing: Boolean): AndroidAudioRouteEvidence {
    if (!clockAdvancing) return AndroidAudioRouteEvidence()
    val device = routedDevice ?: return AndroidAudioRouteEvidence()
    val product =
        device.productName
            ?.toString()
            .orEmpty()
            .filterNot { it == '\r' || it == '\n' || it.isISOControl() }
            .take(MAX_AUDIO_ROUTE_PRODUCT_LENGTH)
    val type = audioDeviceTypeLabel(device.type)
    return AndroidAudioRouteEvidence(
        label = if (product.isBlank()) type else "$type · $product",
        verified = true,
        encodings = runCatching { device.encodings.toSet() }.getOrDefault(emptySet()),
    )
}

/**
 * Resolves object-audio output without promoting a backwards-compatible carrier into Atmos.
 *
 * [independentTrueHdAtmosSinkEvidence] is deliberately not inferred from
 * ENCODING_DOLBY_TRUEHD: Android's public carrier declaration does not prove that the receiver
 * accepted the Atmos object extension. A vendor/HDMI integration may provide that evidence later.
 */
internal fun resolveDolbyAtmosOutputMode(
    sourceCodec: YAudioCodec?,
    sinkCodec: YAudioCodec?,
    outputAdvancing: Boolean,
    route: AndroidAudioRouteEvidence,
    declaredExactTransport: Boolean,
    independentTrueHdAtmosSinkEvidence: Boolean = false,
    spatializedPcm: Boolean = false,
): YDolbyAtmosOutputMode {
    if (!outputAdvancing) return YDolbyAtmosOutputMode.None
    if (spatializedPcm) {
        return if (sourceCodec.isDolbyAtmosSource()) {
            YDolbyAtmosOutputMode.AtmosSourceSpatializedPcm
        } else {
            YDolbyAtmosOutputMode.None
        }
    }
    return when (sourceCodec) {
        YAudioCodec.Eac3Joc ->
            if (
                sinkCodec == YAudioCodec.Eac3Joc &&
                declaredExactTransport &&
                route.verified &&
                AudioFormat.ENCODING_E_AC3_JOC in route.encodings
            ) {
                YDolbyAtmosOutputMode.Eac3JocPassthrough
            } else {
                YDolbyAtmosOutputMode.CarrierOnly
            }
        YAudioCodec.TrueHdAtmos ->
            if (
                sinkCodec == YAudioCodec.TrueHdAtmos &&
                declaredExactTransport &&
                route.verified &&
                AudioFormat.ENCODING_DOLBY_TRUEHD in route.encodings &&
                independentTrueHdAtmosSinkEvidence
            ) {
                YDolbyAtmosOutputMode.TrueHdAtmosPassthrough
            } else {
                YDolbyAtmosOutputMode.TrueHdCarrierPassthrough
            }
        else -> YDolbyAtmosOutputMode.None
    }
}

internal fun YAudioCodec?.isDolbyAtmosSource(): Boolean = this == YAudioCodec.Eac3Joc || this == YAudioCodec.TrueHdAtmos

private fun audioDeviceTypeLabel(type: Int): String =
    when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "内置扬声器"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "听筒"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "有线耳机"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "有线耳麦"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "蓝牙音频"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "蓝牙通话"
        AudioDeviceInfo.TYPE_HDMI -> "HDMI"
        AudioDeviceInfo.TYPE_HDMI_ARC -> "HDMI ARC"
        AudioDeviceInfo.TYPE_HDMI_EARC -> "HDMI eARC"
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB 音频"
        AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "远程混音"
        else -> "音频设备($type)"
    }

private const val MAX_AUDIO_ROUTE_PRODUCT_LENGTH = 80
private const val DEFAULT_ROUTED_OUTPUT_STALE_AFTER_NS = 500_000_000L
