package com.yfuse.core.remote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RemotePairingTest {
    private val nobody: (String) -> Boolean = { false }

    @Test
    fun a_new_phone_waits_until_it_is_let_in_or_already_trusted() {
        val waiting = RemotePairing().connected("phone-a", "小米 14", nobody)
        assertFalse(waiting.admits("phone-a"))
        assertTrue(waiting.allow("phone-a").admits("phone-a"))
        val trusted = RemotePairing().connected("phone-a", "小米 14") { it == "phone-a" }
        assertTrue(trusted.admits("phone-a"))
        assertFalse(trusted.admits("phone-b"), "a phone never heard of is not let in")
    }

    @Test
    fun a_phone_that_reconnects_keeps_its_answer_and_its_name() {
        val allowed =
            RemotePairing()
                .connected("phone-a", "小米 14", nobody)
                .allow("phone-a")
        val again = allowed.connected("phone-a", null, nobody)
        assertEquals(listOf(RemoteControlPhone("phone-a", "小米 14", allowed = true)), again.phones)
        // Gone and back while the television still hosts: 允许一次 still holds.
        val back = allowed.disconnected("phone-a", remaining = 0).connected("phone-a", "小米 14", nobody)
        assertTrue(back.admits("phone-a"))
    }

    @Test
    fun letting_a_phone_go_ends_its_allowance() {
        val released =
            RemotePairing()
                .connected("phone-a", null, nobody)
                .allow("phone-a")
                .release("phone-a")
        assertEquals(emptyList(), released.phones)
        assertFalse(released.connected("phone-a", null, nobody).admits("phone-a"))
    }

    @Test
    fun trust_is_only_for_a_phones_own_id() {
        val standIn = RemotePairing().connected("~2b1f8c3e", null) { true }
        assertFalse(standIn.admits("~2b1f8c3e"))
        assertFalse(standIn.phones.single().rememberable)
    }

    @Test
    fun unnamed_phones_are_asked_about_again_whenever_one_more_connects() {
        val one = RemotePairing().connected(null, null, nobody).allow(UNNAMED_REMOTE_PHONE)
        assertTrue(one.admits(null))
        val two = one.connected(null, null, nobody)
        assertFalse(two.admits(null))
        assertEquals(1, two.phones.size)
        // Which unnamed phone left is unknown, so the rest keep their answer until none is on.
        val allowedTwo = two.allow(UNNAMED_REMOTE_PHONE)
        assertTrue(allowedTwo.disconnected(null, remaining = 1).admits(null))
        assertEquals(emptyList(), allowedTwo.disconnected(null, remaining = 0).phones)
        // And no trust or 允许一次 carries an unnamed phone past that.
        assertFalse(RemotePairing().heard(null) { true }.admits(null))
    }

    @Test
    fun the_relay_hears_of_a_phone_each_time_it_is_let_in_and_not_otherwise() {
        val waiting = RemotePairing().connected("phone-a", "小米 14", nobody)
        assertEquals(emptyList(), waiting.admittedSince(RemotePairing()))
        val allowed = waiting.allow("phone-a")
        assertEquals(listOf("phone-a"), allowed.admittedSince(waiting))
        assertEquals(emptyList(), allowed.connected("phone-a", null, nobody).admittedSince(allowed))
        assertEquals(emptyList(), allowed.heard("phone-a", nobody).admittedSince(allowed))
        // Back after its network blinked, while 允许一次 holds: in at once, on a connection that waits.
        val gone = allowed.disconnected("phone-a", remaining = 0)
        assertEquals(listOf("phone-a"), gone.connected("phone-a", "小米 14", nobody).admittedSince(gone))
        // A trusted phone is let in as it connects; letting one go lets nobody in.
        val trusted = RemotePairing().connected("phone-b", null) { true }
        assertEquals(listOf("phone-b"), trusted.admittedSince(RemotePairing()))
        assertEquals(emptyList(), trusted.release("phone-b").admittedSince(trusted))
    }

    @Test
    fun hosting_again_with_no_phone_left_starts_over() {
        val pairing = RemotePairing().connected("phone-a", null, nobody)
        assertEquals(pairing, pairing.hosted(count = 1))
        assertEquals(emptyList(), pairing.hosted(count = 0).phones)
        assertEquals(pairing, pairing.heard("phone-a", nobody), "a phone already listed is not added twice")
    }
}
