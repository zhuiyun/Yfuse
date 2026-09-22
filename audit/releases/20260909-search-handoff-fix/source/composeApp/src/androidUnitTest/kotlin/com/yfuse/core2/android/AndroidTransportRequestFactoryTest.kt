package com.yfuse.core2.android

import com.yfuse.core2.network.YByteRange
import com.yfuse.core2.network.YSourceProtocol
import com.yfuse.core2.network.YTransportCredentials
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class AndroidTransportRequestFactoryTest {
    @Test
    fun `random access request preserves credentials and exact range`() {
        val credentials = YTransportCredentials.UsernamePassword("viewer", "secret", "media")

        val request =
            yCoreRandomAccessRequest(
                uri = "smb://nas/videos/movie.mkv",
                protocol = YSourceProtocol.Smb,
                startInclusive = 64,
                endInclusive = 127,
                headers = mapOf("User-Agent" to "Yfuse"),
                credentials = credentials,
            )

        assertEquals(YByteRange(64, 127), request.range)
        assertEquals(YSourceProtocol.Smb, request.protocol)
        assertSame(credentials, request.credentials)
    }
}
