package com.yfuse.core.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class NativeCrashIsolationTest {
    @Test
    fun classifiesEachNativeOwnerWithoutBlamingSharedFfmpeg() {
        assertEquals(
            NativePlaybackComponent.Mpv,
            classifyNativePlaybackCrash("#00 pc libmpv.so mpv_render_context_render"),
        )
        assertEquals(
            NativePlaybackComponent.Mdk,
            classifyNativePlaybackCrash("#00 pc libyfuse-mdk-jni.so MDKPlayer"),
        )
        assertEquals(
            NativePlaybackComponent.YCoreDemux,
            classifyNativePlaybackCrash("#00 pc libycore_demux.so demux_read"),
        )
        assertEquals(
            NativePlaybackComponent.Unknown,
            classifyNativePlaybackCrash("#00 pc libavcodec.so decode"),
        )
    }

    @Test
    fun redactsUrlsCredentialsAndMediaPaths() {
        val redacted =
            redactNativeCrashText(
                "https://alice:secret@example.test/movie.mkv?api_key=secret " +
                    "token=abc /storage/emulated/0/Movies/private-title.mkv",
            )

        assertFalse("secret" in redacted)
        assertFalse("private-title" in redacted)
        assertFalse("alice" in redacted)
        assertFalse("abc" in redacted)
    }
}
