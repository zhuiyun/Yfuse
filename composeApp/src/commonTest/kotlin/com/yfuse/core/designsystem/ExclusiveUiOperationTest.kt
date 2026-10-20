package com.yfuse.core.designsystem

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExclusiveUiOperationTest {
    @Test
    fun repeated_taps_before_a_frame_launch_only_once_and_allow_retry_after_completion() =
        runTest {
            val operation = ExclusiveUiOperation()
            val finish = CompletableDeferred<Unit>()
            var writes = 0
            assertTrue(
                operation.launch(this, "save") {
                    writes++
                    finish.await()
                },
            )
            assertFalse(operation.launch(this, "save") { writes++ })
            assertEquals(1, writes)
            finish.complete(Unit)
            runCurrent()
            assertFalse(operation.busy)
            assertTrue(operation.launch(this, "retry") { writes++ })
            assertEquals(2, writes)
        }

    @Test
    fun leaving_a_screen_cancels_work_and_releases_the_gate() =
        runTest {
            val operation = ExclusiveUiOperation()
            operation.launch(this, "import") { CompletableDeferred<Unit>().await() }
            coroutineContext.cancelChildren()
            runCurrent()
            assertFalse(operation.busy)
        }
}
