package com.yfuse.feature.servers

import com.yfuse.core.model.MediaServerKind
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LoginFormInputTest {
    @Test
    fun a_form_left_as_it_opened_holds_no_input() {
        val opened = LoginForm()

        assertFalse(opened.hasInputSince(opened))
        assertFalse(opened.copy(submitting = true, error = "登录失败").hasInputSince(opened))
    }

    @Test
    fun anything_typed_or_picked_counts_as_input() {
        val opened = LoginForm()

        assertTrue(opened.copy(host = "media.example.com").hasInputSince(opened))
        assertTrue(opened.copy(password = "secret").hasInputSince(opened))
        assertTrue(opened.copy(kind = MediaServerKind.Plex).hasInputSince(opened))
    }

    @Test
    fun an_edit_is_measured_against_the_saved_server_it_opened_with() {
        val saved =
            LoginForm(
                serverName = "家里",
                host = "192.168.1.2",
                port = "8096",
                https = false,
                username = "admin",
            )

        assertFalse(saved.hasInputSince(saved))
        assertTrue(saved.copy(serverName = "客厅").hasInputSince(saved))
        assertTrue(LoginForm().hasInputSince(saved))
    }

    @Test
    fun whether_protocol_and_port_were_picked_is_bookkeeping_not_input() {
        val opened = LoginForm()

        assertFalse(opened.copy(protocolChosen = true, portChosen = true).hasInputSince(opened))
    }

    @Test
    fun private_addresses_localhost_and_mdns_names_are_lan_hosts() {
        listOf(
            "192.168.1.8",
            "10.0.0.2",
            "172.16.0.1",
            "172.31.255.254",
            "127.0.0.1",
            "localhost",
            "nas.local",
            "NAS.Local.",
        ).forEach { assertTrue(isLanServerHost(it), it) }
    }

    @Test
    fun public_hosts_and_unfinished_addresses_are_not_lan_hosts() {
        listOf(
            "media.example.com",
            "8.8.8.8",
            "172.32.0.1",
            "192.169.1.1",
            "192.168.1",
            "192.168.1.",
            "192.168.1.300",
            "192.168.1.8.9",
            "nas",
            "local",
        ).forEach { assertFalse(isLanServerHost(it), it) }
    }
}
