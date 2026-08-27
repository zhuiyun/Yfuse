package com.yfuse.core2.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AndroidYCoreHttpProxyTest {
    @Test
    fun accepts_only_one_explicit_forward_byte_range() {
        assertEquals(YCoreHttpByteRange(0L, null), parseYCoreHttpByteRange("bytes=0-"))
        assertEquals(YCoreHttpByteRange(41L, 99L), parseYCoreHttpByteRange("bytes=41-99"))
        assertNull(parseYCoreHttpByteRange("bytes=-100"))
        assertNull(parseYCoreHttpByteRange("bytes=100-99"))
        assertNull(parseYCoreHttpByteRange("bytes=0-1,4-5"))
        assertNull(parseYCoreHttpByteRange("items=0-1"))
    }
}
