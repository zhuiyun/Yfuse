package com.yfuse.core2.test

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class YMediaTestSuiteTest {
    @Test
    fun `device observation captures required runtime health dimensions`() {
        val observation =
            YMediaTestObservation(
                caseId = "dv-p8-1",
                elapsedMs = 60_000L,
                completed = true,
                timedOut = false,
                droppedFrames = 2,
                decoderFailures = 0,
                maximumAbsoluteAvDriftMs = 3L,
                peakPssBytes = 128L * 1024L * 1024L,
                maximumThermalStatus = 2,
                batteryDeltaPermille = -10,
                dolbyVisionProfile = "p8.1",
                videoOutputMode = "native_dolby_vision",
                audioCodec = "eac3-joc",
                audioOutputMode = "eac3_joc_passthrough",
                serverTranscodeUsed = false,
                audioOutputVerified = true,
                dolbyAtmosOutput = true,
                dolbyRpuApplied = true,
                seekCycles = 1_000,
                surfaceRecreations = 1_000,
                queueTransitions = 144,
                continuousSoakMinutes = 480,
                queueSoakMinutes = 1_440,
            )

        assertTrue(observation.completed)
        assertEquals(2, observation.droppedFrames)
        assertEquals(3L, observation.maximumAbsoluteAvDriftMs)
        assertEquals(2, observation.maximumThermalStatus)
        assertEquals("eac3-joc", observation.audioCodec)
        assertEquals("eac3_joc_passthrough", observation.audioOutputMode)
        assertTrue(observation.audioOutputVerified)
        assertTrue(observation.dolbyAtmosOutput)
        assertTrue(observation.dolbyRpuApplied)
        assertTrue(!observation.serverTranscodeUsed)
        assertEquals(1_000, observation.seekCycles)
        assertEquals(1_000, observation.surfaceRecreations)
        assertEquals(144, observation.queueTransitions)
        assertEquals(480, observation.continuousSoakMinutes)
        assertEquals(1_440, observation.queueSoakMinutes)
    }

    @Test
    fun `device observation rejects impossible operation evidence`() {
        assertFailsWith<IllegalArgumentException> {
            YMediaTestObservation(
                caseId = "invalid",
                elapsedMs = 1L,
                completed = false,
                timedOut = false,
                seekCycles = -1,
            )
        }
    }

    @Test
    fun `incomplete corpus reports every missing coverage family`() {
        val suite =
            YMediaTestSuite(
                operations = setOf("open"),
                cases =
                    listOf(
                        YMediaTestCase(
                            id = "basic",
                            relativePath = "basic.mp4",
                            videoCodec = "h264",
                            bitDepth = 8,
                            frameRate = 24.0,
                            container = "mp4",
                            audioCodec = "aac",
                            height = 720,
                            bitrateBitsPerSecond = 1_000_000L,
                        ),
                    ),
            )

        val errors = suite.validationErrors().joinToString("\n")
        assertTrue("missing operations" in errors)
        assertTrue("missing video" in errors)
        assertTrue("missing Dolby Vision" in errors)
        assertTrue("missing bitrate: 150Mbps+" in errors)
    }

    @Test
    fun `corpus paths cannot escape the supplied media root`() {
        val suite =
            YMediaTestSuite(
                operations = emptySet(),
                cases =
                    listOf(
                        YMediaTestCase(
                            id = "escape",
                            relativePath = "../private/movie.mkv",
                            videoCodec = "hevc",
                            bitDepth = 10,
                            frameRate = 23.976,
                            container = "mkv",
                            audioCodec = "truehd",
                            height = 2160,
                            bitrateBitsPerSecond = 80_000_000L,
                        ),
                    ),
            )

        assertTrue(suite.validationErrors().any { "unsafe relative path" in it })
    }

    @Test
    fun `observed media facts prove the manifest declaration`() {
        val declared =
            mediaCase(
                dolbyVisionProfile = "p8.1",
                subtitle = "pgs",
            )
        val observed =
            YMediaObservedFacts(
                videoCodec = "video/dolby-vision",
                bitDepth = 10,
                hdr = "DolbyVision",
                dolbyVisionProfile = "p8.1",
                frameRate = 23.97602,
                container = "Matroska",
                audioCodecs = setOf("audio/eac3-joc"),
                subtitleFormats = setOf("HdmvPgsSubtitle"),
                height = 2160,
                bitrateBitsPerSecond = 62_000_000L,
            )

        assertTrue(declared.observedMetadataErrors(observed).isEmpty())
    }

    @Test
    fun `manifest labels cannot substitute for different observed media`() {
        val errors =
            mediaCase(
                dolbyVisionProfile = "p7_fel",
                subtitle = "pgs",
            ).observedMetadataErrors(
                YMediaObservedFacts(
                    videoCodec = "h264",
                    bitDepth = 8,
                    hdr = "SDR",
                    frameRate = 30.0,
                    container = "mp4",
                    audioCodecs = setOf("aac"),
                    height = 1080,
                    bitrateBitsPerSecond = 5_000_000L,
                ),
            ).joinToString("\n")

        assertTrue("video codec" in errors)
        assertTrue("Dolby Vision profile" in errors)
        assertTrue("audio declared" in errors)
        assertTrue("subtitle declared" in errors)
        assertTrue("bitrate declared" in errors)
    }

    private fun mediaCase(
        dolbyVisionProfile: String?,
        subtitle: String?,
    ): YMediaTestCase =
        YMediaTestCase(
            id = "matrix-case",
            relativePath = "matrix-case.mkv",
            videoCodec = "hevc",
            bitDepth = 10,
            hdr = "DolbyVision",
            dolbyVisionProfile = dolbyVisionProfile,
            frameRate = 23.976,
            container = "mkv",
            audioCodec = "eac3",
            subtitle = subtitle,
            height = 2160,
            bitrateBitsPerSecond = 60_000_000L,
        )
}
