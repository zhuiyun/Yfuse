package com.yfuse.watch

import kotlin.test.Test
import kotlin.test.assertEquals

class CalendarOcrCacheTest {
    @Test
    fun cache_persists_success_and_short_lived_failure_per_provider_and_image() {
        CalendarOcrCache.inMemory().use { cache ->
            assertEquals(CalendarOcrCacheLookup.Miss, cache.lookup("paddle", "image-a"))

            cache.putSuccess("paddle", "image-a", "8月26日 1集")
            assertEquals(
                CalendarOcrCacheLookup.Success("8月26日 1集"),
                cache.lookup("paddle", "image-a"),
            )
            assertEquals(CalendarOcrCacheLookup.Miss, cache.lookup("ocr-space", "image-a"))

            cache.putFailure("ocr-space", "image-a")
            assertEquals(CalendarOcrCacheLookup.RecentFailure, cache.lookup("ocr-space", "image-a"))
        }
    }
}
