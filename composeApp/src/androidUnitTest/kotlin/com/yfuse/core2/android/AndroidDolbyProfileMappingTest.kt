package com.yfuse.core2.android

import android.media.MediaCodecInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AndroidDolbyProfileMappingTest {
    @Test
    fun `Android Dolby profile bits map to semantic bitstream profiles`() {
        assertEquals(4, MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheDtr.toSemanticDolbyVisionProfile())
        assertEquals(5, MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheStn.toSemanticDolbyVisionProfile())
        assertEquals(7, MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheDtb.toSemanticDolbyVisionProfile())
        assertEquals(8, MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheSt.toSemanticDolbyVisionProfile())
        assertEquals(9, MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvavSe.toSemanticDolbyVisionProfile())
        assertEquals(10, MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvav110.toSemanticDolbyVisionProfile())
    }

    @Test
    fun `non Dolby profile is not accepted as a Dolby profile`() {
        assertNull(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10.toSemanticDolbyVisionProfile())
    }
}
