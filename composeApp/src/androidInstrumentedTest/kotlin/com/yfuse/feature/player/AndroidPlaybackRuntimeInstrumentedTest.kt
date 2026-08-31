package com.yfuse.feature.player

import android.graphics.SurfaceTexture
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yfuse.BuildConfig
import com.yfuse.core2.android.FfmpegNativeBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AndroidPlaybackRuntimeInstrumentedTest {
    @Test
    fun packaged_player_runtime_matches_the_selected_profile() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val declaredTargetPackage =
            context.packageManager
                .getInstrumentationInfo(instrumentation.componentName, 0)
                .targetPackage
        assertEquals(declaredTargetPackage, context.packageName)
        val nativeDirectory = File(context.applicationInfo.nativeLibraryDir)
        if (BuildConfig.YFUSE_NATIVE_ONLY_RUNTIME) {
            assertFalse(File(nativeDirectory, "libmpv.so").isFile)
            assertFalse(File(nativeDirectory, "libplayer.so").isFile)
            assertFalse(File(nativeDirectory, "libmdk.so").isFile)
            assertTrue(File(nativeDirectory, "libycore_demux.so").isFile)
            assertTrue(File(nativeDirectory, "libycore_gpu.so").isFile)
            assertClassUnavailable("androidx.media3.exoplayer.ExoPlayer")
            assertClassUnavailable("dev.jdtech.mpv.MPVLib")
        } else {
            Class.forName("dev.jdtech.mpv.MPVLib", true, javaClass.classLoader)
            assertTrue(File(nativeDirectory, "libmpv.so").isFile)
            assertTrue(File(nativeDirectory, "libplayer.so").isFile)
        }
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
                ).setAudioFormat(
                    AudioFormat
                        .Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE_HZ)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build(),
                ).setBufferSizeInBytes(minBufferBytes)
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

    @Test
    fun standalone_ycore_native_apis_load_from_the_packaged_artifact() {
        if (!BuildConfig.YFUSE_NATIVE_ONLY_RUNTIME) return

        assertTrue("FFmpeg JNI bridge failed to load", FfmpegNativeBridge.available)
        assertTrue("FFmpeg software decode API is missing", FfmpegNativeBridge.softwareDecodeAvailable)
        assertTrue("libass renderer API is missing", FfmpegNativeBridge.assRendererAvailable)
        assertTrue("libbluray disc API is missing", FfmpegNativeBridge.discNavigationAvailable)
    }

    private companion object {
        const val SAMPLE_RATE_HZ = 48_000

        fun assertClassUnavailable(className: String) {
            val result = runCatching { Class.forName(className, false, javaClass.classLoader) }
            assertTrue("$className unexpectedly entered the native-only APK", result.isFailure)
            assertTrue(result.exceptionOrNull() is ClassNotFoundException)
        }
    }
}
