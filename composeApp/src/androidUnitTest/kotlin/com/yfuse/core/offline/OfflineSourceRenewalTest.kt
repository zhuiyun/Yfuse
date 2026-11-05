package com.yfuse.core.offline

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OfflineSourceRenewalTest {
    @Test
    fun originAuthenticationIsNotTreatedAsAnExpiredCdnLink() {
        assertFalse(shouldRenewOfflineSource(401, redirected = false, negotiated = false))
        assertTrue(shouldRenewOfflineSource(403, redirected = true, negotiated = false))
        assertTrue(shouldRenewOfflineSource(410, redirected = false, negotiated = true))
        assertFalse(shouldRenewOfflineSource(500, redirected = true, negotiated = true))
        assertEquals(DownloadFailureKind.Authentication, offlineFailureKind(OfflineHttpException(401)))
        assertEquals(
            DownloadFailureKind.Source,
            offlineFailureKind(OfflineHttpException(401, accountAuthentication = false)),
        )
    }

    @Test
    fun renewalPreservesRangeAndValidatorButNeverLoopsOnASecondRefusal() =
        runBlocking {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(403))
                server.enqueue(MockResponse().setResponseCode(403))
                var renewals = 0
                val connection =
                    openRenewableOfflineTransfer(
                        server.url("/expired").toUrl(),
                        "Yfuse-test",
                        true,
                        renewSource = {
                            renewals++
                            server.url("/fresh").toUrl()
                        },
                    ) {
                        setRequestProperty("Range", "bytes=1024-")
                        setRequestProperty("If-Range", "\"same-object\"")
                    }
                try {
                    assertEquals(403, connection.responseCode)
                } finally {
                    connection.disconnect()
                }
                assertEquals(1, renewals)
                assertEquals("/expired", server.takeRequest().path)
                val refreshed = server.takeRequest()
                assertEquals("/fresh", refreshed.path)
                assertEquals("bytes=1024-", refreshed.getHeader("Range"))
                assertEquals("\"same-object\"", refreshed.getHeader("If-Range"))
                assertEquals(2, server.requestCount)
            }
        }
}
