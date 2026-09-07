package com.yfuse.feature.calendar

import com.yfuse.core.model.CalendarDay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame

class CalendarLoadDeadlineTest {
    @Test
    fun parent_timeout_does_not_emit_a_calendar_failure() =
        runTest {
            var deliveredResult = false
            assertFailsWith<TimeoutCancellationException> {
                withTimeout(10) {
                    loadCalendarWithDeadline(timeoutMillis = 100) {
                        delay(1_000)
                        Result.success(emptyList())
                    }
                    deliveredResult = true
                }
            }
            assertFalse(deliveredResult)
        }

    @Test
    fun explicit_cancellation_is_propagated_with_its_reason() =
        runTest {
            val cancelled = CancellationException("screen closed")
            val thrown =
                assertFailsWith<CancellationException> {
                    loadCalendarWithDeadline { throw cancelled }
                }
            assertEquals(cancelled.message, thrown.message)
        }

    @Test
    fun a_successful_load_is_returned_unchanged() =
        runTest {
            val days = listOf(CalendarDay("2026-08-25", emptyList()))

            val result = loadCalendarWithDeadline(timeoutMillis = 100) { Result.success(days) }

            assertEquals(days, result.getOrThrow())
        }

    @Test
    fun a_repository_failure_is_not_rewritten_as_a_timeout() =
        runTest {
            val failure = IllegalStateException("TMDB rejected the request")

            val result =
                loadCalendarWithDeadline(timeoutMillis = 100) {
                    Result.failure<List<CalendarDay>>(failure)
                }

            assertSame(failure, result.exceptionOrNull())
        }

    @Test
    fun the_whole_load_has_a_deadline_even_when_the_loader_never_returns() =
        runTest {
            val result =
                loadCalendarWithDeadline(timeoutMillis = 100) {
                    delay(10_000)
                    Result.success(emptyList())
                }

            assertIs<CalendarLoadTimeoutException>(result.exceptionOrNull())
            assertEquals("日历加载超时，请检查网络后重试", result.exceptionOrNull()?.message)
        }
}
