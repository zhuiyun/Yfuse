package com.yfuse.core2.test

import kotlinx.serialization.Serializable
import kotlin.math.abs

@Serializable
data class YMediaTestCase(
    val id: String,
    val relativePath: String,
    val videoCodec: String,
    val bitDepth: Int,
    val hdr: String = "SDR",
    val dolbyVisionProfile: String? = null,
    val frameRate: Double,
    val container: String,
    val audioCodec: String,
    val subtitle: String? = null,
    val height: Int,
    val bitrateBitsPerSecond: Long,
)

/**
 * Metadata observed from the media itself by the device runner.
 *
 * Keeping this model in common code makes the release-corpus truth check independently testable:
 * manifest labels are coverage requirements, never evidence about the file they point at.
 */
data class YMediaObservedFacts(
    val videoCodec: String,
    val bitDepth: Int,
    val hdr: String,
    val dolbyVisionProfile: String? = null,
    val frameRate: Double,
    val container: String,
    val audioCodecs: Set<String>,
    val subtitleFormats: Set<String> = emptySet(),
    val height: Int,
    val bitrateBitsPerSecond: Long,
)

fun YMediaTestCase.observedMetadataErrors(observed: YMediaObservedFacts): List<String> =
    buildList {
        mismatch("video codec", canonicalVideoCodec(videoCodec), canonicalVideoCodec(observed.videoCodec))
        mismatch("bit depth", bitDepth, observed.bitDepth)
        mismatch("HDR", canonicalHdr(hdr), canonicalHdr(observed.hdr))
        mismatch(
            "Dolby Vision profile",
            dolbyVisionProfile?.let(::canonicalDolbyVisionProfile),
            observed.dolbyVisionProfile?.let(::canonicalDolbyVisionProfile),
        )
        if (abs(frameRate - observed.frameRate) > frameRateTolerance(frameRate)) {
            add("$id: frame rate declared=$frameRate observed=${observed.frameRate}")
        }
        mismatch("container", canonicalContainer(container), canonicalContainer(observed.container))

        val expectedAudio = canonicalAudioCodec(audioCodec)
        val observedAudio = observed.audioCodecs.mapTo(mutableSetOf(), ::canonicalAudioCodec)
        if (expectedAudio !in observedAudio) {
            add("$id: audio declared=$expectedAudio observed=${observedAudio.sorted().joinToString()}")
        }
        subtitle?.let { declaredSubtitle ->
            val expectedSubtitle = canonicalSubtitle(declaredSubtitle)
            val observedSubtitles = observed.subtitleFormats.mapTo(mutableSetOf(), ::canonicalSubtitle)
            if (expectedSubtitle !in observedSubtitles) {
                add(
                    "$id: subtitle declared=$expectedSubtitle " +
                        "observed=${observedSubtitles.sorted().joinToString()}",
                )
            }
        }
        mismatch("height", height, observed.height)
        if (!bitrateMatches(bitrateBitsPerSecond, observed.bitrateBitsPerSecond)) {
            add(
                "$id: bitrate declared=$bitrateBitsPerSecond " +
                    "observed=${observed.bitrateBitsPerSecond}",
            )
        }
    }

private fun <T> MutableList<String>.mismatch(
    label: String,
    declared: T,
    observed: T,
) {
    if (declared != observed) add("metadata $label declared=$declared observed=$observed")
}

private fun canonicalVideoCodec(value: String): String =
    when (value.normalizedToken()) {
        "avc", "h264", "videoavc" -> "h264"
        "h265", "hevc", "videohevc", "videodolbyvision" -> "hevc"
        "av1", "videoav01" -> "av1"
        else -> value.normalizedToken()
    }

private fun canonicalHdr(value: String): String =
    when (value.normalizedToken()) {
        "dolbyvision", "dv" -> "dolbyvision"
        "hdr10plus" -> "hdr10+"
        else -> value.normalizedToken()
    }

private fun canonicalDolbyVisionProfile(value: String): String =
    value
        .trim()
        .lowercase()
        .replace('-', '_')
        .replace("profile", "p")

private fun canonicalContainer(value: String): String =
    when (value.normalizedToken()) {
        "matroska" -> "mkv"
        "mpegts", "mpeg2ts" -> "ts"
        else -> value.normalizedToken()
    }

private fun canonicalAudioCodec(value: String): String =
    when (value.normalizedToken()) {
        "audioaac", "aaclc" -> "aac"
        "audioac3" -> "ac3"
        "audioeac3", "audioeac3joc", "eac3joc" -> "eac3"
        "audiotruehd", "truehdatmos" -> "truehd"
        "audiodtshd", "dtsma", "dtsx" -> "dts-hd"
        else -> value.normalizedToken()
    }

private fun canonicalSubtitle(value: String): String =
    when (value.normalizedToken()) {
        "subrip" -> "srt"
        "ssa" -> "ass"
        "hdmvpgssubtitle" -> "pgs"
        else -> value.normalizedToken()
    }

private fun String.normalizedToken(): String =
    trim()
        .lowercase()
        .filter(Char::isLetterOrDigit)

private fun frameRateTolerance(declared: Double): Double = maxOf(FPS_EPSILON, declared * 0.001)

private fun bitrateMatches(
    declared: Long,
    observed: Long,
): Boolean {
    if (declared <= 0L || observed <= 0L) return false
    val lower = declared * 70L / 100L
    val upper = declared * 130L / 100L
    return observed in lower..upper
}

@Serializable
data class YMediaTestSuite(
    val version: Int = CURRENT_VERSION,
    val operations: Set<String>,
    val cases: List<YMediaTestCase>,
) {
    fun validationErrors(): List<String> =
        buildList {
            if (version != CURRENT_VERSION) add("manifest version must be $CURRENT_VERSION")
            if (cases.isEmpty()) add("media test suite contains no cases")
            val duplicateIds =
                cases
                    .groupingBy { it.id }
                    .eachCount()
                    .filterValues { it > 1 }
                    .keys
            if (duplicateIds.isNotEmpty()) add("duplicate case ids: ${duplicateIds.sorted().joinToString()}")
            cases.forEach { case ->
                if (case.id.isBlank()) add("case id must not be blank")
                if (!case.relativePath.isSafeRelativeMediaPath()) add("${case.id}: unsafe relative path")
                if (case.bitDepth !in setOf(8, 10, 12)) add("${case.id}: unsupported bit depth")
                if (case.frameRate <= 0.0 || !case.frameRate.isFinite()) add("${case.id}: invalid frame rate")
                if (case.height <= 0) add("${case.id}: invalid height")
                if (case.bitrateBitsPerSecond <= 0L) add("${case.id}: invalid bitrate")
            }
            addMissing("operations", REQUIRED_OPERATIONS - operations.normalized())
            addMissing("video", REQUIRED_VIDEO_VARIANTS - videoVariants())
            addMissing("Dolby Vision", REQUIRED_DOLBY_VARIANTS - cases.valuesOf(YMediaTestCase::dolbyVisionProfile))
            addMissing("HDR", REQUIRED_HDR_VARIANTS - cases.valuesOf(YMediaTestCase::hdr))
            addMissing("container", REQUIRED_CONTAINERS - cases.valuesOf(YMediaTestCase::container))
            addMissing("audio", REQUIRED_AUDIO - cases.valuesOf(YMediaTestCase::audioCodec))
            val dolbyAudio =
                cases
                    .filter { it.dolbyVisionProfile != null }
                    .valuesOf(YMediaTestCase::audioCodec)
            addMissing("Dolby audio", REQUIRED_DOLBY_AUDIO - dolbyAudio)
            addMissing("subtitle", REQUIRED_SUBTITLES - cases.mapNotNull(YMediaTestCase::subtitle).normalized())
            val missingFps =
                REQUIRED_FRAME_RATES.filterNot { required ->
                    cases.any {
                        abs(it.frameRate - required) <
                            FPS_EPSILON
                    }
                }
            if (missingFps.isNotEmpty()) add("missing FPS: ${missingFps.joinToString()}")
            if (cases.none { it.height <= 720 }) add("missing resolution: 720p")
            if (cases.none { it.height >= 4320 }) add("missing resolution: 8K")
            if (cases.none { it.bitrateBitsPerSecond <= 1_000_000L }) add("missing bitrate: 1Mbps")
            if (cases.none { it.bitrateBitsPerSecond >= 150_000_000L }) add("missing bitrate: 150Mbps+")
        }

    private fun videoVariants(): Set<String> =
        cases.mapTo(mutableSetOf()) { "${it.videoCodec.normalized()}:${it.bitDepth}" }

    private fun <T> List<YMediaTestCase>.valuesOf(selector: (YMediaTestCase) -> T?): Set<String> =
        mapNotNull(selector).mapTo(mutableSetOf()) { it.toString().normalized() }

    private fun MutableList<String>.addMissing(
        label: String,
        missing: Set<String>,
    ) {
        if (missing.isNotEmpty()) add("missing $label: ${missing.sorted().joinToString()}")
    }

    companion object {
        const val CURRENT_VERSION = 1
    }
}

/** One device-run observation emitted by the instrumented compatibility runner. */
@Serializable
data class YMediaTestObservation(
    val caseId: String,
    val elapsedMs: Long,
    val completed: Boolean,
    val timedOut: Boolean,
    val failureCategory: String? = null,
    val droppedFrames: Int = 0,
    val decoderFailures: Int = 0,
    val maximumAbsoluteAvDriftMs: Long = 0L,
    val peakPssBytes: Long = 0L,
    val maximumThermalStatus: Int = 0,
    val batteryDeltaPermille: Int = 0,
    /** Structured Dolby trace emitted in place of a manual true-device checklist. */
    val dolbyVisionProfile: String? = null,
    val videoOutputMode: String? = null,
    val audioOutputMode: String? = null,
    val serverTranscodeUsed: Boolean = false,
    val dolbyRpuApplied: Boolean = false,
    val dolbyEnhancementLayerComposed: Boolean = false,
) {
    init {
        require(caseId.isNotBlank())
        require(elapsedMs >= 0L)
        require(droppedFrames >= 0)
        require(decoderFailures >= 0)
        require(maximumAbsoluteAvDriftMs >= 0L)
        require(peakPssBytes >= 0L)
        require(maximumThermalStatus >= 0)
    }
}

private fun String.isSafeRelativeMediaPath(): Boolean {
    val normalized = replace('\\', '/')
    return isNotBlank() &&
        !normalized.startsWith('/') &&
        !normalized.substringBefore('/').contains(':') &&
        normalized.split('/').none { it.isBlank() || it == "." || it == ".." }
}

private fun String.normalized(): String = trim().lowercase()

private fun Iterable<String>.normalized(): Set<String> = mapTo(mutableSetOf(), String::normalized)

private val REQUIRED_OPERATIONS =
    setOf(
        "open",
        "play",
        "seek",
        "pause",
        "resume",
        "track_switch",
        "subtitle_switch",
        "surface_recreate",
        "background",
        "foreground",
        "finish",
        "next_episode",
    )
private val REQUIRED_VIDEO_VARIANTS = setOf("h264:8", "h264:10", "hevc:8", "hevc:10", "av1:8", "av1:10")
private val REQUIRED_DOLBY_VARIANTS = setOf("p5", "p7_mel", "p7_fel", "p8.1", "p8.4")
private val REQUIRED_HDR_VARIANTS = setOf("hdr10", "hdr10+", "hlg")
private val REQUIRED_CONTAINERS = setOf("mp4", "mkv", "ts", "m2ts", "iso")
private val REQUIRED_AUDIO = setOf("aac", "ac3", "eac3", "truehd", "dts-hd")
private val REQUIRED_DOLBY_AUDIO = setOf("eac3-joc", "truehd-atmos")
private val REQUIRED_SUBTITLES = setOf("srt", "ass", "pgs")
private val REQUIRED_FRAME_RATES = listOf(23.976, 24.0, 25.0, 29.97, 50.0, 59.94, 120.0)
private const val FPS_EPSILON = 0.002
