package com.yfuse.core.network

import android.security.NetworkSecurityPolicy
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** MockEngine cannot exercise Android's policy loaded from the packaged XML. */
@RunWith(AndroidJUnit4::class)
class NetworkSecurityPolicyInstrumentedTest {
    @Test
    fun shared_account_host_allows_the_user_configured_http_media_service() {
        val policy = NetworkSecurityPolicy.getInstance()
        assertTrue(policy.isCleartextTrafficPermitted("47.112.219.60"))
        assertTrue(policy.isCleartextTrafficPermitted("192.168.1.20"))
        assertTrue(policy.isCleartextTrafficPermitted("media.example.com"))
    }

    @Test
    fun metadata_and_plex_account_hosts_still_reject_plaintext() {
        val policy = NetworkSecurityPolicy.getInstance()
        for (host in listOf("api.themoviedb.org", "image.tmdb.org", "api.trakt.tv", "plex.tv", "app.plex.tv")) {
            assertFalse(host, policy.isCleartextTrafficPermitted(host))
        }
    }
}
