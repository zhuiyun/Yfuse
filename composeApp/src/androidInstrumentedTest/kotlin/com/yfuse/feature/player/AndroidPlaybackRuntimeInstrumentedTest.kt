package com.yfuse.feature.player

import android.graphics.SurfaceTexture
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AndroidPlaybackRuntimeInstrumentedTest {
    @Test
    fun packaged_native_player_libraries_load_on_the_device() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        Class.forName("dev.jdtech.mpv.MPVLib", true, javaClass.classLoader)

        assertEquals("com.yfuse", context.packageName)
        val nativeDirectory = File(context.applicationInfo.nativeLibraryDir)
        assertTrue(File(nativeDirectory, "libmpv.so").isFile)
        assertTrue(File(nativeDirectory, "libplayer.so").isFile)
    }

    @Test
    fun surface_and_pcm_output_resources_can_be_created_and_released() {
        val surfaceTexture = SurfaceTexture(0)
        val surface = Surface(surfaceTexture)
        val minBufferBytes =
            AudioTrack.getMinBufferSize(
                SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
        assertTrue(minBufferBytes > 0)
        val audioTrack =
            AudioTrack
                .Builder()
                .setAudioAttributes(
                    AudioAttributes
                        .Builder()
                        .setLegacyStreamType(AudioManager.STREAM_MUSIC)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat
                        .Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE_HZ)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build(),
                )
                .setBufferSizeInBytes(minBufferBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

        try {
            assertTrue(surface.isValid)
            assertEquals(AudioTrack.STATE_INITIALIZED, audioTrack.state)
        } finally {
            audioTrack.release()
            surface.release()
            surfaceTexture.release()
        }
    }

    @Test
    fun any_advertised_bluray_marker_must_match_the_complete_pinned_artifact() {
        val capabilities = detectMpvNativeBuildCapabilities()

        if (capabilities.libbluray) {
            assertTrue(capabilities.pinnedYfuseBluRayArtifact)
        } else {
            assertFalse(capabilities.pinnedYfuseBluRayArtifact)
        }
    }

    private companion object {
        const val SAMPLE_RATE_HZ = 48_000
    }
}
