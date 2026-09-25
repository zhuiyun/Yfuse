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
}
