package com.yfuse.feature.servers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServerConnectionPermissionTest {
    @Test
    fun denied_lan_connections_do_not_attempt_login_or_quick_connect() {
        for (endpoint in listOf(
            "http://192.168.1.4:8096",
            "https://10.0.0.2",
            "http://172.16.0.2",
            "http://nas.local",
            "http://nas",
            "http://[fd00::1]",
            "http://[fe80::1]",
            "http://[::ffff:192.168.1.2]",
        )) {
            for (action in listOf(ServersIntent.Submit, ServersIntent.StartQuickConnect)) {
                assertEquals(
                    ServersIntent.LocalNetworkPermissionDenied,
                    connectionIntentAfterPermission(action, endpoint, false),
                    endpoint,
                )
                assertEquals(action, connectionIntentAfterPermission(action, endpoint, true), endpoint)
            }
        }
    }

    @Test
    fun denial_does_not_block_internet_or_same_profile_loopback() {
        for (endpoint in listOf(
            "https://media.example.com",
            "http://8.8.8.8",
            "http://172.32.0.1",
            "http://localhost:1234",
            "http://127.0.0.1",
            "http://[::1]",
            "http://[::ffff:127.0.0.1]",
        )) {
            assertFalse(serverUsesLocalNetwork(endpoint), endpoint)
            assertEquals(ServersIntent.Submit, connectionIntentAfterPermission(ServersIntent.Submit, endpoint, false))
        }
    }

    @Test
    fun only_connection_actions_request_permission() {
        assertTrue(ServersIntent.Submit.connectsToServer())
        assertTrue(ServersIntent.StartQuickConnect.connectsToServer())
        assertTrue(ServersIntent.SelectPlexCloudServer("local").connectsToServer())
        assertFalse(ServersIntent.HostChanged("nas.local").connectsToServer())
        assertFalse(ServersIntent.DismissDialog.connectsToServer())
        assertFalse(ServersIntent.CancelQuickConnect.connectsToServer())
    }
}
