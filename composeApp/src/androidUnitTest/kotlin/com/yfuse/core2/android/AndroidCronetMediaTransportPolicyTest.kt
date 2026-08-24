package com.yfuse.core2.android

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AndroidCronetMediaTransportPolicyTest {
    @Test
    fun response_and_body_waits_are_bounded_before_the_ycore_startup_watchdog() {
        val source = source()

        assertFalse("chunks.take()" in source, "Cronet body reads must never block indefinitely")
        assertTrue("chunks.poll(" in source, "Cronet body reads need a bounded wait")

        val openTimeout = timeout(source, "CRONET_OPEN_TIMEOUT_SECONDS")
        val bodyTimeout = timeout(source, "CRONET_BODY_READ_TIMEOUT_SECONDS")
        assertTrue(openTimeout in 1 until YCORE_DEFAULT_STARTUP_TIMEOUT_SECONDS)
        assertTrue(bodyTimeout in 1 until YCORE_DEFAULT_STARTUP_TIMEOUT_SECONDS)
    }

    @Test
    fun a_terminal_failure_returns_already_copied_bytes_before_throwing() {
        val source = source()

        assertTrue(
            "if (outputOffset > offset) return@withContext outputOffset - offset" in source,
            "The adaptive transport can resume exactly only after partial bytes are reported",
        )
    }

    private fun timeout(
        source: String,
        name: String,
    ): Int {
        val match = assertNotNull(Regex("$name\\s*=\\s*(\\d+)L").find(source))
        return match.groupValues[1].toInt()
    }

    private fun source(): String =
        sequenceOf(
            File("src/androidMain/kotlin/com/yfuse/core2/android/AndroidCronetMediaTransport.kt"),
            File("composeApp/src/androidMain/kotlin/com/yfuse/core2/android/AndroidCronetMediaTransport.kt"),
        ).firstOrNull(File::isFile)
            ?.readText()
            ?: error("Cannot locate AndroidCronetMediaTransport.kt from ${File(".").absolutePath}")

    private companion object {
        const val YCORE_DEFAULT_STARTUP_TIMEOUT_SECONDS = 15
    }
}
