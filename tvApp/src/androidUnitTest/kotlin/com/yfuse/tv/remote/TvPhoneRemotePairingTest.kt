package com.yfuse.tv.remote

import com.yfuse.tv.ui.tvPhoneRemoteQuestion
import kotlin.test.Test
import kotlin.test.assertEquals

class TvPhoneRemotePairingTest {
    @Test
    fun trusting_a_phone_again_moves_it_first_with_its_latest_name() {
        val trusted =
            listOf(TrustedPhone("phone-a", "小米 14"), TrustedPhone("phone-b", "iPhone"))
                .trusting(TrustedPhone("phone-b", "iPhone 16"))
        assertEquals(listOf(TrustedPhone("phone-b", "iPhone 16"), TrustedPhone("phone-a", "小米 14")), trusted)
    }

    @Test
    fun a_household_of_phones_is_kept_and_the_longest_trusted_goes_first() {
        val full = (1..MAX_TRUSTED_PHONES).map { TrustedPhone("phone-$it") }
        val next = full.trusting(TrustedPhone("phone-new"))
        assertEquals(MAX_TRUSTED_PHONES, next.size)
        assertEquals("phone-new", next.first().deviceId)
        assertEquals(full.dropLast(1), next.drop(1))
    }

    @Test
    fun the_trusted_list_survives_a_round_trip_and_nothing_unreadable_lets_a_phone_in() {
        val phones = listOf(TrustedPhone("5f0c1d2e3a4b5c6d", "小米 14"), TrustedPhone("0a1b2c3d4e5f6071", null))
        assertEquals(phones, decodeTrustedPhones(encodeTrustedPhones(phones)))
        assertEquals(emptyList(), decodeTrustedPhones(null))
        assertEquals(emptyList(), decodeTrustedPhones("not json"))
        // The relay's one-connection stand-in, or an id no phone could send, is never kept.
        val kept = decodeTrustedPhones("""[{"deviceId":"~2b1f8c3e"},{"deviceId":"has space"},{"deviceId":"phone-a"}]""")
        assertEquals(listOf(TrustedPhone("phone-a")), kept)
    }

    @Test
    fun the_question_names_the_phone_as_it_names_itself() {
        assertEquals("允许「小米 14」遥控这台电视？", tvPhoneRemoteQuestion("小米 14"))
        assertEquals("允许一部手机遥控这台电视？", tvPhoneRemoteQuestion(null))
        assertEquals("允许一部手机遥控这台电视？", tvPhoneRemoteQuestion(" "))
    }
}
