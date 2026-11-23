    private fun awaitBefore(
        message: String,
        condition: () -> Boolean,
    ) {
        val deadline = SystemClock.uptimeMillis() + 5_000L
        while (!condition() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(20L)
        if (!condition()) {
            val window =
                shell("dumpsys window")
                    .lineSequence()
                    .filter { "mCurrentFocus=" in it || "mFocusedApp=" in it || "mOnBackInvokedCallbackInfo=" in it }
                    .joinToString("; ")
            assertTrue("$message; system window/back callbacks: $window", false)
        }
    }

    private fun awaitAfter(
        message: String,
        condition: () -> Boolean,
    ) {
        val deadline = SystemClock.uptimeMillis() + 5_000L
        while (true) {
            // A system transition may start again after one idle sample. A second read outside
            // the polling loop would report a timeout immediately, without using the deadline.
            if (condition()) return
            if (SystemClock.uptimeMillis() >= deadline) break
            SystemClock.sleep(20L)
        }
        val window =
            shell("dumpsys window")
                .lineSequence()
                .filter { "mCurrentFocus=" in it || "mFocusedApp=" in it || "mOnBackInvokedCallbackInfo=" in it }
                .joinToString("; ")
        assertTrue("$message; system window/back callbacks: $window", false)
    }

private object SystemClock {
    var now = 0L
    fun uptimeMillis() = now
    fun sleep(ms: Long) { now += ms }
}
private fun shell(command: String) = ""
private fun assertTrue(message: String, value: Boolean) { if (!value) throw AssertionError(message) }
fun main() {
    var samples = 0
    val oldResult = runCatching { awaitBefore("idle") { ++samples == 1 } }
    check(oldResult.exceptionOrNull() is AssertionError && samples == 2)
    check(SystemClock.now == 0L)
    println("before: reproduced false timeout at 0 ms after two samples")
    samples = 0
    awaitAfter("idle") { ++samples == 1 }
    check(samples == 1)
    println("after: accepted the first satisfied sample")
    SystemClock.now = 0
    val timeout = runCatching { awaitAfter("never ready") { false } }
    check(timeout.exceptionOrNull() is AssertionError && SystemClock.now == 5000L)
    println("after: a genuinely false condition still fails at 5000 ms")
}
