package com.yfuse.core.logging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class ThrowableDiagnosticsTest {
    @Test
    fun originSkipsPlatformFramesAndReportsRenamedAppFrame() {
        val failure =
            IllegalStateException("boom").apply {
                stackTrace =
                    arrayOf(
                        frame("android.media.MediaCodec", "native_dequeueOutputBuffer", -2),
                        frame("android.media.MediaCodec", "dequeueOutputBuffer", 3521),
                        frame("ys", "n", 2646),
                        frame("kotlinx.coroutines.DispatchedTask", "run", 104),
                    )
            }

        assertEquals("ys.n:2646", failure.diagnosticOrigin())
    }

    @Test
    fun originFallsBackToFirstFrameWhenEveryFrameIsLibrary() {
        val failure =
            IllegalStateException().apply {
                stackTrace = arrayOf(frame("java.util.ArrayList", "get", 437))
            }

        assertEquals("ArrayList.get:437", failure.diagnosticOrigin())
    }

    @Test
    fun rootCauseUnwrapsNestedCausesAndStopsOnCycles() {
        val root = IllegalArgumentException("root")
        val wrapped = RuntimeException("outer", IllegalStateException("middle", root))

        assertSame(root, wrapped.diagnosticRootCause())

        val cyclic = RuntimeException("a")
        val other = RuntimeException("b", cyclic)
        cyclic.initCause(other)
        // A cause cycle must terminate instead of spinning forever.
        cyclic.diagnosticRootCause()
    }

    @Test
    fun typeNameIsTheSimpleClassName() {
        assertEquals("IllegalStateException", IllegalStateException().diagnosticTypeName())
    }

    private fun frame(
        className: String,
        method: String,
        line: Int,
    ) = StackTraceElement(className, method, "SourceFile", line)
}
