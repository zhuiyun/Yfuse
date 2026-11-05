package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MpvVideoReadinessGateTest {
    @Test
    fun seeking_and_same_item_reload_reject_old_buffer_samples_until_the_new_native_boundary() {
        val gate = MpvVideoReadinessGate()
        gate.onLoadRequested()
        gate.onStartFile()
        gate.onFileLoaded()
        gate.onPlaybackRestart()
        assertTrue(gate.canReportBufferRanges())
        val previous = gate.bufferGeneration()
        gate.onSeekRequested()
        assertFalse(gate.canReportBufferRanges())
        assertTrue(gate.bufferGeneration() > previous)
        gate.onPlaybackRestart()
        assertTrue(gate.canReportBufferRanges())
        val afterSeek = gate.bufferGeneration()
        gate.onLoadRequested()
        gate.onPlaybackRestart() // Queued predecessor restart before START_FILE is not the new item.
        assertFalse(gate.canReportBufferRanges())
        assertTrue(gate.bufferGeneration() > afterSeek)
        gate.onStartFile()
        assertFalse(gate.canReportBufferRanges())
        gate.onFileLoaded()
        gate.onSeekRequested() // Pending resume issued from FILE_LOADED.
        assertFalse(gate.canReportBufferRanges())
        gate.onPlaybackRestart()
        assertTrue(gate.canReportBufferRanges())
    }

    @Test
    fun configured_placeholder_cannot_confirm_a_media_frame_while_http_headers_are_pending() {
        val gate = MpvVideoReadinessGate()
        assertFalse(gate.canReportRendering(outputConfigured = true, surfaceValid = true))
        gate.onLoadRequested()
        gate.onStartFile()
        repeat(3) { gate.onPlaybackRestart() }
        assertFalse(gate.canReportRendering(outputConfigured = true, surfaceValid = true))
        gate.onFileLoaded()
        assertFalse(gate.canReportRendering(outputConfigured = true, surfaceValid = true))
        gate.onPlaybackRestart()
        assertTrue(gate.canReportRendering(outputConfigured = true, surfaceValid = true))
    }

    @Test
    fun replacement_discards_old_output_evidence_until_the_new_media_actually_starts() {
        val gate = MpvVideoReadinessGate()
        gate.onStartFile()
        gate.onFileLoaded()
        gate.onPlaybackRestart()
        assertTrue(gate.canReportRendering(outputConfigured = true, surfaceValid = true))

        gate.onLoadRequested()
        // Queued predecessor notifications before the next START_FILE cannot prime the new file.
        gate.onFileLoaded()
        gate.onPlaybackRestart()
        assertFalse(gate.canReportRendering(outputConfigured = true, surfaceValid = true))
        gate.onStartFile()
        assertFalse(gate.canReportRendering(outputConfigured = true, surfaceValid = true))
        gate.onFileLoaded()
        gate.onPlaybackRestart()
        assertTrue(gate.canReportRendering(outputConfigured = true, surfaceValid = true))
    }

    @Test
    fun decoded_media_requires_a_configured_valid_surface_to_report_rendering() {
        val gate = MpvVideoReadinessGate()
        gate.onStartFile()
        gate.onFileLoaded()
        gate.onPlaybackRestart()
        assertFalse(gate.canReportRendering(outputConfigured = false, surfaceValid = true))
        assertFalse(gate.canReportRendering(outputConfigured = true, surfaceValid = false))
        assertTrue(gate.canReportRendering(outputConfigured = true, surfaceValid = true))
    }
}
