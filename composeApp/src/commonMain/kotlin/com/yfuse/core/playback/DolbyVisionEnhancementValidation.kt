package com.yfuse.core.playback

import com.yfuse.core.model.MediaVersion

/**
 * What YCore can actually prove about a Profile 7 enhancement layer at the final output.
 *
 * Source metadata and output evidence are deliberately separated. A server saying `ElPresentFlag=1`
 * proves only that the source carries an EL; it does not prove that the selected decoder composed it.
 */
enum class DolbyVisionP7OutputEvidence {
    /** The selected source is not a Profile 7 + EL case, so FEL validation does not apply. */
    NotApplicable,

    /** The source needs validation but no trustworthy output evidence has been collected. */
    NotMeasured,

    /** The base layer reached the output but no RPU/EL application has been proven. */
    BaseLayerOnly,

    /** The output trace proves BL + RPU handling, but does not prove enhancement-layer composition. */
    BaseLayerWithRpu,

    /** A physical/output trace explicitly proves the enhancement layer participated in composition. */
    EnhancementLayerComposed,
}

/**
 * Redacted evidence carried by validation/diagnostics. No media URL, token, account, device id or
 * title is needed to answer whether a FEL claim is justified.
 */
data class DolbyVisionP7ValidationEvidence(
    val profile: Int?,
    val sourceRpuPresent: Boolean?,
    val sourceEnhancementLayerPresent: Boolean?,
    val sourceBaseLayerPresent: Boolean?,
    /** Decoder/render trace proves base-layer frames reached the selected video output. */
    val outputBaseLayerDecoded: Boolean = false,
    /** Output trace proves the RPU was applied; a Dolby badge/decoder name alone is not enough. */
    val outputRpuApplied: Boolean = false,
    /** Physical/output trace proves the source enhancement layer was actually composed. */
    val outputEnhancementLayerComposed: Boolean = false,
)

data class DolbyVisionP7ValidationResult(
    val evidence: DolbyVisionP7OutputEvidence,
    val canClaimFel: Boolean,
    val reason: String,
)

fun MediaVersion.dolbyVisionP7ValidationEvidence(): DolbyVisionP7ValidationEvidence =
    DolbyVisionP7ValidationEvidence(
        profile = dolbyProfile,
        sourceRpuPresent = video?.dolbyRpuPresent,
        sourceEnhancementLayerPresent = video?.dolbyEnhancementLayerPresent,
        sourceBaseLayerPresent = video?.dolbyBaseLayerPresent,
    )

fun evaluateDolbyVisionP7Output(evidence: DolbyVisionP7ValidationEvidence): DolbyVisionP7ValidationResult {
    val profileSevenWithEl =
        evidence.profile == 7 && evidence.sourceEnhancementLayerPresent == true
    if (!profileSevenWithEl) {
        return DolbyVisionP7ValidationResult(
            evidence = DolbyVisionP7OutputEvidence.NotApplicable,
            canClaimFel = false,
            reason = "当前片源不是需要 FEL 验证的 Dolby Vision P7 + EL",
        )
    }

    // Enhancement-layer composition is the only state allowed to authorize an FEL output claim.
    // The source must also contain a base layer: contradictory source metadata is never promoted by
    // a downstream trace because the validation input itself is not trustworthy enough.
    if (
        evidence.sourceBaseLayerPresent != false &&
        evidence.outputEnhancementLayerComposed
    ) {
        return DolbyVisionP7ValidationResult(
            evidence = DolbyVisionP7OutputEvidence.EnhancementLayerComposed,
            canClaimFel = true,
            reason = "输出证据已证明 Dolby Vision P7 enhancement layer 参与合成",
        )
    }

    if (evidence.outputBaseLayerDecoded && evidence.outputRpuApplied) {
        return DolbyVisionP7ValidationResult(
            evidence = DolbyVisionP7OutputEvidence.BaseLayerWithRpu,
            canClaimFel = false,
            reason = "已证明 BL + RPU 输出，但没有 enhancement-layer composition 证据",
        )
    }

    if (evidence.outputBaseLayerDecoded) {
        return DolbyVisionP7ValidationResult(
            evidence = DolbyVisionP7OutputEvidence.BaseLayerOnly,
            canClaimFel = false,
            reason = "仅证明 Dolby Vision P7 base layer 输出，不能声明 FEL",
        )
    }

    return DolbyVisionP7ValidationResult(
        evidence = DolbyVisionP7OutputEvidence.NotMeasured,
        canClaimFel = false,
        reason =
            when {
                evidence.sourceBaseLayerPresent == false ->
                    "服务器报告 P7 enhancement layer 存在但 base layer 缺失，输出证据不可信"
                evidence.sourceRpuPresent == false ->
                    "服务器报告 P7 enhancement layer 存在但 RPU 缺失，需要重新探测片源"
                else ->
                    "片源为 Dolby Vision P7 + EL，但尚无可信的 enhancement-layer 输出测量"
            },
    )
}
