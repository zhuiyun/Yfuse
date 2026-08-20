package com.yfuse.core2.test

import kotlin.test.Test
import kotlin.test.assertTrue

class YMediaTestSuiteTest {
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
}
