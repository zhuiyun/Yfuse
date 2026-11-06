package com.yfuse.feature.player

import com.yfuse.core.data.SenPlayerEnhancementLevel
import kotlin.test.Test
import kotlin.test.assertEquals

class SenPlayerPictureControlsTest {
    @Test
    fun previous_acceptance_does_not_claim_a_new_request_has_applied() {
        val previous =
            SenPlayerEnhancementState(
                level = SenPlayerEnhancementLevel.Low,
                status = SenPlayerEnhancementStatus.Accepted,
            )
        assertEquals("正在应用 高", senPlayerEnhancementStatusLabel(SenPlayerEnhancementLevel.High, previous))
        assertEquals("内核已接受，等待状态", senPlayerEnhancementStatusLabel(SenPlayerEnhancementLevel.Low, previous))
        assertEquals(
            "已设置，当前未生效",
            senPlayerEnhancementStatusLabel(SenPlayerEnhancementLevel.Low, previous.copy(active = false)),
        )
        assertEquals(
            "已生效 · 低",
            senPlayerEnhancementStatusLabel(SenPlayerEnhancementLevel.Low, previous.copy(active = true)),
        )
    }

    @Test
    fun unavailable_gate_explains_why_the_saved_request_is_not_active() {
        val blocked =
            SenPlayerEnhancementState(
                level = SenPlayerEnhancementLevel.Medium,
                status = SenPlayerEnhancementStatus.Rejected,
                detail = "当前屏幕不支持 HDR10",
            )
        assertEquals(
            "当前屏幕不支持 HDR10",
            senPlayerEnhancementStatusLabel(SenPlayerEnhancementLevel.Medium, blocked),
        )
        assertEquals(
            "内核未接受此设置",
            senPlayerEnhancementStatusLabel(SenPlayerEnhancementLevel.Medium, blocked.copy(detail = "")),
        )
    }

    @Test
    fun disabling_waits_for_the_native_active_flag_to_clear() {
        val disabling =
            SenPlayerEnhancementState(
                level = SenPlayerEnhancementLevel.Off,
                status = SenPlayerEnhancementStatus.Accepted,
                active = true,
            )
        assertEquals("关闭中", senPlayerEnhancementStatusLabel(SenPlayerEnhancementLevel.Off, disabling))
        assertEquals(
            "已关闭",
            senPlayerEnhancementStatusLabel(SenPlayerEnhancementLevel.Off, disabling.copy(active = false)),
        )
    }
}
