package com.yfuse.feature.library

import kotlin.test.Test
import kotlin.test.assertEquals

class LibraryFreshnessTest {
    private val now = 1_786_406_400_000L // 2026-08-11T00:00:00Z

    @Test
    fun formatsRelativeFreshnessAtStableBoundaries() {
        assertEquals("时间未知", formatLibraryUpdatedAt(null, now))
        assertEquals("时间未知", formatLibraryUpdatedAt(0L, now))
        assertEquals("刚刚", formatLibraryUpdatedAt(now - 59_999L, now))
        assertEquals("1 分钟前", formatLibraryUpdatedAt(now - 60_000L, now))
        assertEquals("59 分钟前", formatLibraryUpdatedAt(now - 59 * 60_000L, now))
        assertEquals("1 小时前", formatLibraryUpdatedAt(now - 60 * 60_000L, now))
        assertEquals("23 小时前", formatLibraryUpdatedAt(now - 23 * 60 * 60_000L, now))
    }

    @Test
    fun formatsOlderOrClockSkewedValuesAsExplicitUtcDates() {
        assertEquals("2026-08-10 UTC", formatLibraryUpdatedAt(now - 24 * 60 * 60_000L, now))
        assertEquals("2026-08-11 UTC", formatLibraryUpdatedAt(now + 6 * 60_000L, now))
        assertEquals("刚刚", formatLibraryUpdatedAt(now + 5 * 60_000L, now))
    }
}
