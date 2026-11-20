    private fun awaitCondition(
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
