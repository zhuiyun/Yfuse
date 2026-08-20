package com.yfuse.core2.android

import android.media.AudioFormat
import android.os.Build
import com.yfuse.core2.capability.YAudioCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AndroidEncodedAudioTrackRenderNodeTest {
    @Test
    fun `maps only Android encoded passthrough codecs`() {
        assertEquals(
            AudioFormat.ENCODING_E_AC3_JOC,
            androidEncodedAudioEncoding(YAudioCodec.Eac3Joc, Build.VERSION_CODES.P),
        )
        assertEquals(AudioFormat.ENCODING_DOLBY_TRUEHD, androidEncodedAudioEncoding(YAudioCodec.TrueHdAtmos))
        assertEquals(AudioFormat.ENCODING_DTS_HD, androidEncodedAudioEncoding(YAudioCodec.DtsHd))
        assertEquals(AudioFormat.ENCODING_DTS_HD, androidEncodedAudioEncoding(YAudioCodec.DtsX))
        assertNull(androidEncodedAudioEncoding(YAudioCodec.Aac))
        assertNull(androidEncodedAudioEncoding(YAudioCodec.Flac))
    }

    @Test
    fun `uses standard eac3 carrier for joc before api 28`() {
        assertEquals(
            AudioFormat.ENCODING_E_AC3,
            androidEncodedAudioEncoding(YAudioCodec.Eac3Joc, Build.VERSION_CODES.O_MR1),
        )
        assertEquals(
            AudioFormat.ENCODING_E_AC3_JOC,
            androidEncodedAudioEncoding(YAudioCodec.Eac3Joc, Build.VERSION_CODES.P),
        )
    }
}
