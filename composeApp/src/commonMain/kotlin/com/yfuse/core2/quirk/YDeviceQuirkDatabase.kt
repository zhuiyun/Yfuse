package com.yfuse.core2.quirk

import com.yfuse.core2.capability.YContainer
import com.yfuse.core2.capability.YDeviceCapabilities
import com.yfuse.core2.capability.YHdrType
import com.yfuse.core2.capability.YVideoCodec
import com.yfuse.core2.strategy.YPlaybackRequest

data class YDeviceIdentity(
    val manufacturer: String,
    val model: String,
    val soc: String,
    val androidApi: Int,
)

sealed interface YTextMatch {
    fun matches(value: String): Boolean

    data class Exact(
        val value: String,
    ) : YTextMatch {
        override fun matches(value: String): Boolean = value.equals(this.value, ignoreCase = true)
    }

    data class Prefix(
        val value: String,
    ) : YTextMatch {
        override fun matches(value: String): Boolean = value.startsWith(this.value, ignoreCase = true)
    }
}

enum class YDeviceQuirkAction {
    DisableTunnel,
    DisableDecoder,
    DisableDolbyVision,
    DisableAudioPassthrough,
    ForceEnhancedDemux,
    ForceSoftwareFallback,
}

data class YDeviceQuirkRule(
    val id: String,
    val manufacturer: YTextMatch? = null,
    val model: YTextMatch? = null,
    val soc: YTextMatch? = null,
    val minimumApi: Int? = null,
    val maximumApi: Int? = null,
    val decoder: YTextMatch? = null,
    val container: YContainer? = null,
    val videoCodec: YVideoCodec? = null,
    val hdrType: YHdrType? = null,
    val dolbyVisionProfile: Int? = null,
    val minimumWidth: Int? = null,
    val maximumWidth: Int? = null,
    val minimumHeight: Int? = null,
    val maximumHeight: Int? = null,
    val actions: Set<YDeviceQuirkAction>,
) {
    init {
        require(id.isNotBlank())
        require(actions.isNotEmpty())
        require(minimumApi == null || maximumApi == null || minimumApi <= maximumApi)
        require(minimumWidth == null || minimumWidth >= 0)
        require(maximumWidth == null || maximumWidth >= 0)
        require(minimumHeight == null || minimumHeight >= 0)
        require(maximumHeight == null || maximumHeight >= 0)
    }

    fun matches(
        identity: YDeviceIdentity,
        request: YPlaybackRequest,
        decoderName: String?,
    ): Boolean =
        manufacturer.matchesIfPresent(identity.manufacturer) &&
            model.matchesIfPresent(identity.model) &&
            soc.matchesIfPresent(identity.soc) &&
            (minimumApi == null || identity.androidApi >= minimumApi) &&
            (maximumApi == null || identity.androidApi <= maximumApi) &&
            (decoder == null || decoderName != null && decoder.matches(decoderName)) &&
            (container == null || request.container == container) &&
            (videoCodec == null || request.video.codec == videoCodec) &&
            (hdrType == null || request.video.hdrType == hdrType) &&
            (dolbyVisionProfile == null || request.video.dolbyVisionProfile == dolbyVisionProfile) &&
            (minimumWidth == null || request.video.width >= minimumWidth) &&
            (maximumWidth == null || request.video.width <= maximumWidth) &&
            (minimumHeight == null || request.video.height >= minimumHeight) &&
            (maximumHeight == null || request.video.height <= maximumHeight)
}

data class YQuirkAdjustment(
    val request: YPlaybackRequest,
    val capabilities: YDeviceCapabilities,
    val matchedRuleIds: Set<String>,
)

/** Data-driven device exception layer; product code supplies versioned rules, never model if/else. */
class YDeviceQuirkDatabase(
    private val rules: List<YDeviceQuirkRule> = emptyList(),
) {
    fun adjust(
        identity: YDeviceIdentity,
        request: YPlaybackRequest,
        capabilities: YDeviceCapabilities,
    ): YQuirkAdjustment {
        val globalRules = rules.filter { it.decoder == null && it.matches(identity, request, null) }
        val globalActions = globalRules.flatMapTo(mutableSetOf(), YDeviceQuirkRule::actions)
        val adjustedDecoders =
            capabilities.videoDecoders.mapNotNull { decoder ->
                val decoderRules = rules.filter { it.matches(identity, request, decoder.name) }
                val actions = globalActions + decoderRules.flatMap(YDeviceQuirkRule::actions)
                when {
                    YDeviceQuirkAction.DisableDecoder in actions -> null
                    else ->
                        decoder.copy(
                            hdrTypes =
                                if (YDeviceQuirkAction.DisableDolbyVision in actions) {
                                    decoder.hdrTypes - YHdrType.DolbyVision
                                } else {
                                    decoder.hdrTypes
                                },
                            dolbyVisionProfiles =
                                if (YDeviceQuirkAction.DisableDolbyVision in actions) {
                                    emptySet()
                                } else {
                                    decoder.dolbyVisionProfiles
                                },
                            tunneledPlayback =
                                decoder.tunneledPlayback && YDeviceQuirkAction.DisableTunnel !in actions,
                        )
                }
            }
        val forceSoftware = YDeviceQuirkAction.ForceSoftwareFallback in globalActions
        val adjustedRequest =
            request.copy(
                platformDemuxSupported =
                    request.platformDemuxSupported &&
                        YDeviceQuirkAction.ForceEnhancedDemux !in globalActions &&
                        !forceSoftware,
                enhancedDemuxSupported = request.enhancedDemuxSupported && !forceSoftware,
                allowAudioPassthrough =
                    request.allowAudioPassthrough &&
                        YDeviceQuirkAction.DisableAudioPassthrough !in globalActions,
                preferTunnel = request.preferTunnel && YDeviceQuirkAction.DisableTunnel !in globalActions,
            )
        val adjustedCapabilities =
            capabilities.copy(
                videoDecoders = if (forceSoftware) emptyList() else adjustedDecoders,
                audioPassthrough =
                    if (YDeviceQuirkAction.DisableAudioPassthrough in globalActions) {
                        emptySet()
                    } else {
                        capabilities.audioPassthrough
                    },
                displayHdrTypes =
                    if (YDeviceQuirkAction.DisableDolbyVision in globalActions) {
                        capabilities.displayHdrTypes - YHdrType.DolbyVision
                    } else {
                        capabilities.displayHdrTypes
                    },
                supportsTunnel = capabilities.supportsTunnel && YDeviceQuirkAction.DisableTunnel !in globalActions,
            )
        val matchedIds =
            buildSet {
                addAll(globalRules.map(YDeviceQuirkRule::id))
                capabilities.videoDecoders.forEach { decoder ->
                    addAll(rules.filter { it.matches(identity, request, decoder.name) }.map(YDeviceQuirkRule::id))
                }
            }
        return YQuirkAdjustment(adjustedRequest, adjustedCapabilities, matchedIds)
    }
}

private fun YTextMatch?.matchesIfPresent(value: String): Boolean = this == null || matches(value)
