package com.yfuse.core2.android

import com.yfuse.core2.api.YPlaybackException
import com.yfuse.core2.api.YPlaybackFailureCategory
import com.yfuse.core2.api.YPlaybackFailureStage
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeDirectStartupPolicyTest {
    @Test
    fun `a Surface attached before anything was read does not seek the source`() {
        // Incident E: prepared at 0, Surface attached, then a seek to 0 flushed two unfed decoders.
        assertFalse(
            nativeDirectSurfaceAttachNeedsSeek(
                inputConsumedSinceReposition = false,
                sourcePositionedAtUs = 0L,
                resumeUs = 0L,
            ),
        )
        // The same after a resume seek that nothing has read past yet.
        assertFalse(
            nativeDirectSurfaceAttachNeedsSeek(
                inputConsumedSinceReposition = false,
                sourcePositionedAtUs = 55_100_000L,
                resumeUs = 55_100_000L,
            ),
        )
    }

    @Test
    fun `a Surface attached after samples were consumed or the position moved seeks`() {
        assertTrue(
            nativeDirectSurfaceAttachNeedsSeek(
                inputConsumedSinceReposition = true,
                sourcePositionedAtUs = 0L,
                resumeUs = 0L,
            ),
        )
        assertTrue(
            nativeDirectSurfaceAttachNeedsSeek(
                inputConsumedSinceReposition = false,
                sourcePositionedAtUs = 0L,
                resumeUs = 29_500_000L,
            ),
        )
    }

    @Test
    fun `a decode or output failure keeps the source for a same-item retry`() {
        assertTrue(keepsSource(YPlaybackFailureCategory.AudioSink, YPlaybackFailureStage.AudioRenderer))
        assertTrue(keepsSource(YPlaybackFailureCategory.Decoder, YPlaybackFailureStage.VideoDecoderQueue))
        assertTrue(keepsSource(YPlaybackFailureCategory.Decoder, YPlaybackFailureStage.AudioDecoderConfigure))
        assertTrue(keepsSource(YPlaybackFailureCategory.Renderer, YPlaybackFailureStage.VideoRenderer))
    }

    @Test
    fun `anything that may implicate the source rebuilds it`() {
        assertFalse(nativeDirectFailureKeepsSource(IllegalStateException("untyped")))
        assertFalse(keepsSource(YPlaybackFailureCategory.Network, YPlaybackFailureStage.Demux))
        assertFalse(keepsSource(YPlaybackFailureCategory.Container, YPlaybackFailureStage.Demux))
        assertFalse(keepsSource(YPlaybackFailureCategory.Unknown, YPlaybackFailureStage.Seek))
        assertFalse(keepsSource(YPlaybackFailureCategory.Drm, YPlaybackFailureStage.VideoDecoderQueue))
        // A decoder category at a source stage is still a source failure.
        assertFalse(keepsSource(YPlaybackFailureCategory.Decoder, YPlaybackFailureStage.SourceOpen))
    }

    private fun keepsSource(
        category: YPlaybackFailureCategory,
        stage: YPlaybackFailureStage,
    ): Boolean =
        nativeDirectFailureKeepsSource(
            YPlaybackException(category = category, stage = stage, cause = IllegalStateException("test")),
        )
}
