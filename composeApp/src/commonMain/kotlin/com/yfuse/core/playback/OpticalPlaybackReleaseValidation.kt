package com.yfuse.core.playback

/** Release evidence for capabilities that cannot be proven by unit tests alone. */
enum class OpticalValidationGate {
    Pass,
    Fail,
    NotMeasured,
}

data class OpticalValidationCheck(
    val gate: OpticalValidationGate,
    val detail: String,
)

/**
 * Redacted physical/native evidence for one candidate build.
 *
 * No server URL, token, account id, disc title or device serial belongs in this structure. It only
 * records whether the exact release artifact/corpus supplied the evidence required to make a public
 * capability claim.
 */
data class OpticalPlaybackValidationInput(
    val nativeAarBuiltAndVerified: Boolean? = null,
    val arm64PageSize16kVerified: Boolean? = null,
    val localIsoMainFeatureVerified: Boolean? = null,
    val localBdmvFilesystemVerified: Boolean? = null,
    val localBdmvSafVerified: Boolean? = null,
    val remoteIsoRangePreflightVerified: Boolean? = null,
    val remoteIsoPlaybackVerified: Boolean? = null,
    val remoteIsoFallbackVerified: Boolean? = null,
    val titleChapterSeekResumeVerified: Boolean? = null,
    val hdmvRootAndPopupMenuVerified: Boolean? = null,
    val hdmvTouchAndDpadVerified: Boolean? = null,
    val multiAngleVerified: Boolean? = null,
    val hdr10Verified: Boolean? = null,
    val hdr10PlusVerified: Boolean? = null,
    val hlgVerified: Boolean? = null,
    val dolbyVisionVerified: Boolean? = null,
    val trueHdAtmosVerified: Boolean? = null,
    val dtsHdVerified: Boolean? = null,
    val pgsVerified: Boolean? = null,
    val largeIso100GiBVerified: Boolean? = null,
    val proRes100GiBVerified: Boolean? = null,
    val ordinaryMediaRegressionVerified: Boolean? = null,
    val soakAndThermalVerified: Boolean? = null,
)

data class OpticalPlaybackValidationReport(
    val checks: Map<String, OpticalValidationCheck>,
) {
    val releaseReady: Boolean
        get() = checks.isNotEmpty() && checks.values.all { it.gate == OpticalValidationGate.Pass }

    val failed: List<String>
        get() = checks.filterValues { it.gate == OpticalValidationGate.Fail }.keys.sorted()

    val notMeasured: List<String>
        get() = checks.filterValues { it.gate == OpticalValidationGate.NotMeasured }.keys.sorted()
}

/**
 * Converts optional evidence into hard release gates. Missing observations are always NotMeasured,
 * never an implicit pass. This mirrors the numeric YCore release evaluator's evidence discipline.
 */
fun evaluateOpticalPlaybackRelease(
    input: OpticalPlaybackValidationInput,
): OpticalPlaybackValidationReport =
    OpticalPlaybackValidationReport(
        checks =
            linkedMapOf(
                "nativeAar" to input.nativeAarBuiltAndVerified.check("custom libmpv/libbluray AAR + provenance verifier"),
                "pageSize16k" to input.arm64PageSize16kVerified.check("ARM64 PT_LOAD >= 16 KiB and Android load"),
                "localIso" to input.localIsoMainFeatureVerified.check("local Blu-ray ISO main feature"),
                "localBdmvFs" to input.localBdmvFilesystemVerified.check("filesystem BDMV VFS"),
                "localBdmvSaf" to input.localBdmvSafVerified.check("persisted SAF BDMV VFS"),
                "remoteRange" to input.remoteIsoRangePreflightVerified.check("remote ISO strict Range preflight"),
                "remoteIso" to input.remoteIsoPlaybackVerified.check("authenticated remote raw ISO playback"),
                "remoteFallback" to input.remoteIsoFallbackVerified.check("native remote ISO -> server fallback"),
                "navigation" to input.titleChapterSeekResumeVerified.check("title/chapter/seek/resume"),
                "hdmvMenu" to input.hdmvRootAndPopupMenuVerified.check("HDMV root/popup menu"),
                "hdmvInput" to input.hdmvTouchAndDpadVerified.check("HDMV touch/D-pad/back input"),
                "multiAngle" to input.multiAngleVerified.check("authored seamless multi-angle"),
                "hdr10" to input.hdr10Verified.check("HDR10 output"),
                "hdr10Plus" to input.hdr10PlusVerified.check("HDR10+ output"),
                "hlg" to input.hlgVerified.check("HLG output"),
                "dolbyVision" to input.dolbyVisionVerified.check("Dolby Vision device/output route"),
                "trueHdAtmos" to input.trueHdAtmosVerified.check("TrueHD/Atmos HDMI/eARC"),
                "dtsHd" to input.dtsHdVerified.check("DTS-HD HDMI/eARC"),
                "pgs" to input.pgsVerified.check("PGS select/render/seek"),
                "largeIso" to input.largeIso100GiBVerified.check("100 GiB+ ISO startup/seek/resume/EOF"),
                "largeProRes" to input.proRes100GiBVerified.check("100 GiB+ MOV/ProRes startup/seek/resume/EOF"),
                "ordinaryRegression" to input.ordinaryMediaRegressionVerified.check("ordinary MP4/MKV/HLS regression"),
                "soakThermal" to input.soakAndThermalVerified.check("8h/24h soak and thermal/power lane"),
            ),
    )

private fun Boolean?.check(detail: String): OpticalValidationCheck =
    when (this) {
        true -> OpticalValidationCheck(OpticalValidationGate.Pass, detail)
        false -> OpticalValidationCheck(OpticalValidationGate.Fail, detail)
        null -> OpticalValidationCheck(OpticalValidationGate.NotMeasured, detail)
    }
