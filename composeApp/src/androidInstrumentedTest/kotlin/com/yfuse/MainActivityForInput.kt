package com.yfuse

import android.app.Activity
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.yfuse.core.security.ServerSessionRecovery
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.fail

/**
 * MainActivity for a test that replaces its content and sends it input, handed over once both
 * are safe to do.
 *
 * It is launched after the saved sessions are restored: until then onCreate defers building the
 * app, and the app's own setContent would land on top of the test's whenever the restore finished.
 * It is returned once its window has focus — see [awaitWindowFocus].
 */
internal fun launchMainActivityForInput(): ActivityScenario<MainActivity> {
    runBlocking { withTimeout(SESSION_RESTORE_TIMEOUT_MS) { ServerSessionRecovery.awaitReady() } }
    val scenario = ActivityScenario.launch(MainActivity::class.java)
    try {
        awaitWindowFocus(scenario)
    } catch (failure: Throwable) {
        // The test never gets this scenario to close, and the next one should not start behind it.
        scenario.close()
        throw failure
    }
    return scenario
}

/**
 * Returns once [scenario]'s window is on screen and focused, so that input sent afterwards reaches it.
 *
 * ActivityScenario hands an activity over once it is RESUMED, but the window manager shows the
 * window only after its first frame has been drawn, which can take a while for MainActivity on the
 * software-rendered CI emulator. A touch injected in between goes to whatever is still on screen —
 * the launcher, or the activity before it: Instrumentation's targeted injection refuses that as
 * another uid's window, and UiAutomation delivers it there, where nothing answers. A window gets
 * focus only once it is shown with nothing focusable in front of it; a failure names the window
 * the window manager has focused instead.
 */
internal fun <A : Activity> awaitWindowFocus(
    scenario: ActivityScenario<A>,
    timeoutMs: Long = WINDOW_FOCUS_TIMEOUT_MS,
) {
    val deadline = SystemClock.uptimeMillis() + timeoutMs
    while (true) {
        var focused = false
        scenario.onActivity { focused = it.hasWindowFocus() }
        if (focused) return
        if (SystemClock.uptimeMillis() >= deadline) break
        SystemClock.sleep(FOCUS_POLL_MS)
    }
    fail("The activity's window did not take focus in $timeoutMs ms; the window manager has ${focusedWindow()}")
}

/** The window manager's own account of the focused window and app, from `dumpsys window`. */
private fun focusedWindow(): String =
    runCatching {
        val dump =
            InstrumentationRegistry
                .getInstrumentation()
                .uiAutomation
                .executeShellCommand("dumpsys window")
                .let { ParcelFileDescriptor.AutoCloseInputStream(it) }
                .bufferedReader()
                .use { it.readText() }
        dump
            .lineSequence()
            .map(String::trim)
            .filter { it.startsWith("mCurrentFocus=") || it.startsWith("mFocusedApp=") }
            .distinct()
            .joinToString("; ")
            .ifEmpty { "no focus in its dump" }
    }.getOrElse { "a dump that could not be read ($it)" }

private const val SESSION_RESTORE_TIMEOUT_MS = 10_000L
private const val WINDOW_FOCUS_TIMEOUT_MS = 15_000L
private const val FOCUS_POLL_MS = 50L
