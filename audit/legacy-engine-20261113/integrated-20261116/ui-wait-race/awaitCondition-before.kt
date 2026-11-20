    private fun awaitCondition(
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
