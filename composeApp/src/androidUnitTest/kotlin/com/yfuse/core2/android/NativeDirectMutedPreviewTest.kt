package com.yfuse.core2.android

import com.yfuse.core2.api.YMediaItem
import com.yfuse.core2.api.YMediaSourceHints
import com.yfuse.core2.api.YPlaybackException
import com.yfuse.core2.api.YPlayerOpenRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeDirectMutedPreviewTest {
    @Test
    fun disabled_audio_never_discovers_tracks_or_requests_a_decoder_even_when_source_declares_audio() {
        val selected =
            selectNativeDirectInitialAudio(
                audioDisabled = true,
                item =
                    YMediaItem(
                        "preview",
                        "http://example.test/trailer.mp4",
                        sourceHints = YMediaSourceHints(audioTrackCount = 2),
                    ),
                trackCount = { error("Preview must not inspect audio tracks") },
                trackFormat = { error("Preview must not read audio formats") },
                plannedAudioOutputPath = null,
                devicePath = { error("Preview must not choose an audio decoder or sink") },
            )
        assertNull(selected.track)
        assertTrue(selected.discovered.isEmpty())
    }

    @Test
    fun ordinary_playback_still_rejects_an_undiscovered_declared_audio_track() {
        assertFailsWith<YPlaybackException> {
            selectNativeDirectInitialAudio(
                audioDisabled = false,
                item =
                    YMediaItem(
                        "film",
                        "https://example.test/film.mp4",
                        sourceHints = YMediaSourceHints(audioTrackCount = 1),
                    ),
                trackCount = { 0 },
                trackFormat = { error("No discovered tracks") },
                plannedAudioOutputPath = null,
                devicePath = { error("No audio format") },
            )
        }
    }

    @Test
    fun native_direct_default_retains_audio_and_preview_configuration_remains_local() {
        val request = YPlayerOpenRequest(listOf(YMediaItem("film", "file:///film.mp4")))
        assertFalse(NativeDirectSessionConfiguration(request).audioDisabled)
        assertTrue(NativeDirectSessionConfiguration(request, audioDisabled = true).audioDisabled)
        assertFalse(NativeDirectSessionConfiguration(request).audioDisabled)
    }

    @Test
    fun preview_is_a_single_http_compatible_item_without_queue_prefetch_or_subtitles() {
        val uri = "http://example.test/trailer.mp4"
        val request = mutedYCorePreviewRequest(uri)
        assertEquals(uri, request.items.single().uri)
        assertTrue(request.autoPlay)
        assertFalse(request.autoNext)
        assertFalse(request.items.single().allowNextItemPreparation)
        assertTrue(
            request.items
                .single()
                .initialTrackSelection
                ?.subtitlesDisabled == true,
        )
    }
}
