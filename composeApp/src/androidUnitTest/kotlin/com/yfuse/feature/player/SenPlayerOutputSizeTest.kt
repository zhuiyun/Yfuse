package com.yfuse.feature.player

import androidx.compose.ui.unit.IntSize
import com.yfuse.core.data.SenPlayerEnhancementLevel
import com.yfuse.core.data.SenPlayerPictureSettings
import com.yfuse.core.model.PlaybackMethod
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SenPlayerOutputSizeTest {
    private val screen = IntSize(1280, 720)
    private val source =
        PlayerMediaVersion(
            id = "version",
            label = "Original",
            detail = "",
            url = "file:///film.mp4",
            transcodeUrl = "",
            fallbackTranscodeUrl = "",
            sourceWidth = 640,
            sourceHeight = 360,
            sourceDisplayAspect = "16:9",
            sourceDynamicRange = "SDR",
        )
    private val item = externalPlaybackItem(source.url, "Film").copy(versions = listOf(source))
    private val plain = SenPlayerPictureSettings()

    @Test
    fun dimensions_follow_each_original_file_and_the_current_window() {
        assertEquals(IntSize(640, 360), size(source))
        assertEquals(
            IntSize(960, 540),
            size(source.copy(sourceWidth = 960, sourceHeight = 540, sourceDisplayAspect = "1.78:1")),
        )
        assertEquals(
            IntSize(640, 480),
            size(source.copy(sourceHeight = 480, sourceDisplayAspect = "4:3"), IntSize(1280, 960)),
        )
        assertNull(size(source.copy(sourceWidth = 1920, sourceHeight = 1080)))
        assertNull(size(source, IntSize(720, 1280)))
        assertNull(size(source, IntSize(640, 360)))
    }

    @Test
    fun preserve_full_output_for_unknown_geometry_hdr_transcodes_rotation_and_picture_enhancements() {
        for (version in listOf(
            source.copy(sourceDisplayAspect = null),
            source.copy(sourceWidth = null),
            source.copy(sourceDisplayAspect = "4:3"),
            source.copy(sourceDisplayAspect = "1:0"),
            source.copy(sourceRotation = 90),
            source.copy(dolbyVision = true),
            source.copy(sourceDynamicRange = "HDR10"),
            source.copy(playMethod = PlaybackMethod.Transcode),
        )) {
            assertNull(size(version))
        }
        assertNull(senPlayerOutputSize(item.copy(versions = emptyList()), screen, VideoScaleMode.Fit, plain))
        assertNull(senPlayerOutputSize(item, screen, VideoScaleMode.Fill, plain))
        assertNull(
            senPlayerOutputSize(
                item,
                screen,
                VideoScaleMode.Fit,
                plain.copy(upscaling = SenPlayerEnhancementLevel.Low),
            ),
        )
        assertNull(
            senPlayerOutputSize(item, screen, VideoScaleMode.Fit, plain.copy(sdrToHdr = SenPlayerEnhancementLevel.Low)),
        )
    }

    private fun size(
        version: PlayerMediaVersion,
        viewport: IntSize = screen,
    ) = senPlayerOutputSize(item.copy(versions = listOf(version)), viewport, VideoScaleMode.Fit, plain)
}
