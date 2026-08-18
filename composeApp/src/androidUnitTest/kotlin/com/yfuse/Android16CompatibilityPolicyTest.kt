package com.yfuse

import com.yfuse.core.offline.hasSufficientOfflineStorage
import com.yfuse.core.offline.missingOfflineStorageBytes
import com.yfuse.core.util.shouldLockCompactScreenOrientation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Android16CompatibilityPolicyTest {
    @Test
    fun phone_orientation_is_preserved_while_api_36_large_screens_can_override_it() {
        assertTrue(shouldLockCompactScreenOrientation(599))
        assertFalse(shouldLockCompactScreenOrientation(600))
    }

    @Test
    fun large_downloads_reserve_space_without_overflowing_size_math() {
        val reserve = 256L * 1024L * 1024L
        assertTrue(hasSufficientOfflineStorage(reserve + 100L, 100L, reserve))
        assertFalse(hasSufficientOfflineStorage(reserve + 99L, 100L, reserve))
        assertEquals(1L, missingOfflineStorageBytes(reserve + 99L, 100L, reserve))
        assertFalse(hasSufficientOfflineStorage(Long.MAX_VALUE, Long.MAX_VALUE, reserve))
    }
}
