package com.yfuse.feature.player

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayerNoticesTest {
    @AfterTest
    fun clear() = PlayerNotices.clear()

    @Test
    fun a_new_line_takes_the_place_of_the_one_showing() {
        PlayerNotices.show("画面：适应")
        val first = PlayerNotices.slot.value.notice!!
        PlayerNotices.show("画面：填充")
        val second = PlayerNotices.slot.value.notice!!

        assertEquals("画面：填充", second.text)
        assertNotEquals(first.id, second.id)
        // The first one's timer running out must not take the second one down.
        PlayerNotices.dismiss(first.id)
        assertEquals(second, PlayerNotices.slot.value.notice)
        PlayerNotices.dismiss(second.id)
        assertNull(PlayerNotices.slot.value.notice)
    }

    @Test
    fun the_same_words_twice_are_two_notices() {
        PlayerNotices.show("当前由房主控制播放")
        val first = PlayerNotices.slot.value.notice!!
        PlayerNotices.dismiss(first.id)
        PlayerNotices.show("当前由房主控制播放")

        assertNotEquals(
            first.id,
            PlayerNotices.slot.value.notice!!
                .id,
        )
    }

    @Test
    fun a_line_to_read_stays_up_longer_than_one_to_glance_at() {
        PlayerNotices.show("画面：适应")
        assertFalse(
            PlayerNotices.slot.value.notice!!
                .longer,
        )
        PlayerNotices.show("切换服务器失败：无法清理旧的服务器转码，请稍后重试")
        assertTrue(
            PlayerNotices.slot.value.notice!!
                .longer,
        )
    }
}
