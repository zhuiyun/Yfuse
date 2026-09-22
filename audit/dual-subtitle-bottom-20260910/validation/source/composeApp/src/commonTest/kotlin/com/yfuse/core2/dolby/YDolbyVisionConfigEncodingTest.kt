package com.yfuse.core2.dolby

import kotlin.test.Test
import kotlin.test.assertEquals

class YDolbyVisionConfigEncodingTest {
    @Test
    fun `Dolby configuration round trips through Android csd-2 wire payload`() {
        val source =
            YDolbyVisionConfig(
                versionMajor = 1,
                versionMinor = 0,
                profile = 8,
                level = 6,
                rpuPresent = true,
                enhancementLayerPresent = false,
                baseLayerPresent = true,
                baseLayerCompatibilityId = 1,
                metadataCompression = 2,
            )

        assertEquals(source, YDolbyVisionConfig.parse(source.toConfigurationBytes()))
    }
}
