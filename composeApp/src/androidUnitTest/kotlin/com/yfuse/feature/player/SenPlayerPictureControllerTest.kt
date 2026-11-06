package com.yfuse.feature.player

import com.yfuse.core.data.SenPlayerEnhancementLevel
import com.yfuse.core.data.SenPlayerPictureSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SenPlayerPictureControllerTest {
    private val available = SenPlayerPictureEligibility(null, null)
    private val requested = SenPlayerPictureSettings(SenPlayerEnhancementLevel.High, SenPlayerEnhancementLevel.Medium)

    @Test
    fun native_rejection_is_independent_and_does_not_repeat_each_poll() {
        val controller = SenPlayerPictureController()
        val sent = mutableListOf<Pair<SenPlayerPictureFeature, SenPlayerEnhancementLevel>>()
        val send = { feature: SenPlayerPictureFeature, level: SenPlayerEnhancementLevel ->
            sent += feature to level
            if (feature == SenPlayerPictureFeature.Upscaling) {
                SenPlayerEnhancementState(level, SenPlayerEnhancementStatus.Rejected, -4)
            } else {
                SenPlayerEnhancementState(level, SenPlayerEnhancementStatus.Accepted)
            }
        }
        repeat(10) { controller.apply(requested, available, send) }
        assertEquals(2, sent.size)
        assertEquals(SenPlayerPictureFeature.Upscaling, sent.first().first)
        assertEquals(-4, controller.state.upscaling.nativeCode)
        assertEquals(SenPlayerEnhancementStatus.Accepted, controller.state.sdrToHdr.status)
        assertNull(controller.state.sdrToHdr.active)
        controller.apply(requested.copy(upscaling = SenPlayerEnhancementLevel.Low), available, send)
        assertEquals(3, sent.size)
        controller.reset()
        controller.apply(requested, available, send)
        assertEquals(5, sent.size)
    }

    @Test
    fun eligibility_changes_clear_old_effects_and_retry_only_when_available_again() {
        val controller = SenPlayerPictureController()
        val sent = mutableListOf<Pair<SenPlayerPictureFeature, SenPlayerEnhancementLevel>>()
        val send = { feature: SenPlayerPictureFeature, level: SenPlayerEnhancementLevel ->
            sent += feature to level
            SenPlayerEnhancementState(level, SenPlayerEnhancementStatus.Accepted)
        }
        controller.apply(requested, available, send)
        val noHdr = available.copy(sdrToHdrUnavailableReason = "HDR 片源")
        repeat(10) { controller.apply(requested, noHdr, send) }
        assertEquals(3, sent.size)
        assertEquals(SenPlayerPictureFeature.SdrToHdr to SenPlayerEnhancementLevel.Off, sent.last())
        assertEquals(SenPlayerEnhancementLevel.Medium, controller.state.sdrToHdr.level)
        assertEquals("HDR 片源", controller.state.sdrToHdr.detail)
        controller.apply(requested, available, send)
        assertEquals(4, sent.size)
        assertEquals(SenPlayerEnhancementLevel.Medium, sent.last().second)
    }

    @Test
    fun a_rejected_disable_is_not_reported_as_successful_suppression() {
        val controller = SenPlayerPictureController()
        val result =
            controller.apply(requested, SenPlayerPictureEligibility()) { _, level ->
                SenPlayerEnhancementState(level, SenPlayerEnhancementStatus.Rejected, -9)
            }
        assertEquals(-9, result.sdrToHdr.nativeCode)
        assertTrue(
            result.sdrToHdr.detail
                .orEmpty()
                .contains("关闭请求未确认"),
        )
    }

    @Test
    fun retired_session_failure_propagates_and_does_not_acknowledge_or_send_other_options() {
        val controller = SenPlayerPictureController()
        var calls = 0
        assertFailsWith<IllegalStateException> {
            controller.apply(requested, available) { _, _ ->
                calls++
                error("retired session")
            }
        }
        assertEquals(1, calls)
        assertEquals(SenPlayerEnhancementStatus.Waiting, controller.state.upscaling.status)
        assertEquals(SenPlayerEnhancementStatus.Waiting, controller.state.sdrToHdr.status)
    }

    @Test
    fun original_source_display_and_native_capability_gates_are_preserved() {
        fun policy(
            hdr: Boolean = true,
            range: String? = "sdr",
            dolby: Int = 0,
            duration: Long = 1000,
            upscale: Boolean? = true,
        ) = senPlayerPictureEligibility(hdr, range, dolby, duration, upscale)
        assertEquals(available, policy())
        assertEquals(available, policy(range = "SDR"))
        assertTrue(policy(hdr = false).sdrToHdrUnavailableReason.orEmpty().contains("HDR10"))
        assertTrue(policy(range = null).sdrToHdrUnavailableReason.orEmpty().contains("等待"))
        assertTrue(policy(range = "hdr10").sdrToHdrUnavailableReason.orEmpty().contains("仅适用"))
        assertTrue(policy(dolby = 8).sdrToHdrUnavailableReason.orEmpty().contains("仅适用"))
        assertTrue(policy(upscale = false).upscalingUnavailableReason.orEmpty().contains("不支持"))
        assertTrue(policy(duration = 0).upscalingUnavailableReason.orEmpty().contains("时长"))
        assertTrue(policy(upscale = null).upscalingUnavailableReason.orEmpty().contains("等待"))
    }
}
