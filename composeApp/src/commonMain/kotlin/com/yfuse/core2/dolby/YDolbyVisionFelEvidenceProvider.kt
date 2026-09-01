package com.yfuse.core2.dolby

/** Context supplied to a trusted decoder/vendor FEL composition trace. */
data class YDolbyVisionFelCompositionRequest(
    val config: YDolbyVisionConfig,
    val decoderName: String?,
    val renderedFrameObserved: Boolean,
    val rpuAccessUnitObserved: Boolean,
    val enhancementLayerAccessUnitObserved: Boolean,
)

/**
 * Extension point for device integrations that can prove decoded FEL contribution independently.
 * The default implementation is deliberately fail-closed.
 */
fun interface YDolbyVisionFelEvidenceProvider {
    fun isFelComposed(request: YDolbyVisionFelCompositionRequest): Boolean
}

object FailClosedYDolbyVisionFelEvidenceProvider : YDolbyVisionFelEvidenceProvider {
    override fun isFelComposed(request: YDolbyVisionFelCompositionRequest): Boolean = false
}

fun verifyDolbyVisionFelComposition(
    request: YDolbyVisionFelCompositionRequest,
    provider: YDolbyVisionFelEvidenceProvider,
): Boolean =
    request.config.profile == 7 &&
        request.config.enhancementLayerPresent &&
        request.renderedFrameObserved &&
        request.rpuAccessUnitObserved &&
        request.enhancementLayerAccessUnitObserved &&
        provider.isFelComposed(request)
