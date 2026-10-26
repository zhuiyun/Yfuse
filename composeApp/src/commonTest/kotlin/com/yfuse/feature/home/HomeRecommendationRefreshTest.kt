package com.yfuse.feature.home

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class HomeRecommendationRefreshTest {
    @Test
    fun checks_immediately_and_while_open_but_stops_when_cancelled() =
        runTest {
            var checks = 0
            val job = backgroundScope.launch { pollHomeRecommendationDate { checks++ } }
            runCurrent()
            assertEquals(1, checks)
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(2, checks)
            job.cancelAndJoin()
            advanceTimeBy(120_000)
            runCurrent()
            assertEquals(2, checks)
            val resumed = backgroundScope.launch { pollHomeRecommendationDate { checks++ } }
            runCurrent()
            assertEquals(3, checks)
            resumed.cancelAndJoin()
        }
}
