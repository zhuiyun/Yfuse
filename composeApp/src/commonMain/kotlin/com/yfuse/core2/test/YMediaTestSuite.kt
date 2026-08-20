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
private val REQUIRED_SUBTITLES = setOf("srt", "ass", "pgs")
private val REQUIRED_FRAME_RATES = listOf(23.976, 24.0, 25.0, 29.97, 50.0, 59.94, 120.0)
private const val FPS_EPSILON = 0.002
