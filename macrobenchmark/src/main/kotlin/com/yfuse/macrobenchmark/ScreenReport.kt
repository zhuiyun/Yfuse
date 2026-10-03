package com.yfuse.macrobenchmark

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until

/*
 * A journey that cannot find what it looks for says what was on screen instead. A failed test's
 * message is the one thing CI's console carries that every reader can reach: the workflow's
 * artifacts sit in a store some reviewers cannot open, and logcat is not kept. The generator
 * failed run after run on "Production navigation never appeared" and nothing else.
 */

/** Fails the journey with [message], followed by what UiAutomator could read at that moment. */
internal fun MacrobenchmarkScope.failOnScreen(message: String): Nothing =
    throw AssertionError("$message\n${screenReport()}")

/** assertTrue, with the screen in the message when [condition] does not hold. */
internal fun MacrobenchmarkScope.assertOnScreen(
    message: String,
    condition: Boolean,
) {
    if (!condition) failOnScreen(message)
}

/** [selector] once it is on screen, waited for up to [timeoutMs]; [message] and the screen if it never is. */
internal fun MacrobenchmarkScope.awaitObject(
    selector: BySelector,
    timeoutMs: Long,
    message: String,
): UiObject2 = device.wait(Until.findObject(selector), timeoutMs) ?: failOnScreen(message)

/**
 * Every window UiAutomator searches, topmost first, and in each the nodes that say something — a
 * text, a description, a resource id — or that can be clicked, selected or scrolled, indented
 * under the nearest such ancestor, with their bounds. Enough to tell a missing dock from a dialog
 * in front of it, a keyboard over it or a caption exposed some other way.
 */
internal fun MacrobenchmarkScope.screenReport(): String =
    runCatching {
        val report = ScreenReport()
        report.line(0, "On screen (${device.currentPackageName} in front):")
        // A window can go while it is being read; the others still say what was there.
        device.windowRoots.forEach { root ->
            runCatching { report.window(root) }.onFailure { report.line(1, "a window that could not be read: $it") }
        }
        report.finish()
    }.getOrElse { "The screen could not be read: $it" }

private class ScreenReport {
    private val out = StringBuilder()
    private var lines = 0
    private var omitted = 0

    fun window(root: AccessibilityNodeInfo) {
        val window = root.window
        val title = window?.title?.let { " \"$it\"" }.orEmpty()
        line(1, "window: ${windowKind(window?.type)} ${root.packageName}$title")
        walk(root, 2)
    }

    fun line(
        depth: Int,
        text: String,
    ) {
        if (lines >= MAX_REPORT_LINES) {
            omitted++
            return
        }
        out.append("  ".repeat(depth)).append(text).append('\n')
        lines++
    }

    fun finish(): String {
        if (omitted > 0) out.append("… and $omitted more lines\n")
        return out.toString().trimEnd()
    }

    private fun walk(
        node: AccessibilityNodeInfo,
        depth: Int,
    ) {
        val description = describe(node)
        if (description != null) line(depth, description)
        val childDepth = if (description != null) depth + 1 else depth
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { walk(it, childDepth) }
        }
    }
}

/** One node's line, or null for a node that neither says nor does anything a journey could find. */
private fun describe(node: AccessibilityNodeInfo): String? {
    val text = node.text?.toString()?.takeIf(String::isNotBlank)
    val description = node.contentDescription?.toString()?.takeIf(String::isNotBlank)
    val id = node.viewIdResourceName
    val states =
        listOfNotNull(
            "clickable".takeIf { node.isClickable },
            "selected".takeIf { node.isSelected },
            "scrollable".takeIf { node.isScrollable },
            "focused".takeIf { node.isFocused },
        )
    if (text == null && description == null && id == null && states.isEmpty()) return null
    val bounds = Rect().also(node::getBoundsInScreen)
    return buildString {
        append(node.className?.toString()?.substringAfterLast('.') ?: "?")
        text?.let { append(" \"").append(it.clipped()).append('"') }
        description?.let { append(" desc=\"").append(it.clipped()).append('"') }
        id?.let { append(" id=").append(it) }
        states.forEach { append(' ').append(it) }
        // Selectors skip an invisible node and everything under it, however well it matches.
        if (!node.isVisibleToUser) append(" invisible")
        append(' ').append(bounds.toShortString())
    }
}

private fun windowKind(type: Int?): String =
    when (type) {
        AccessibilityWindowInfo.TYPE_APPLICATION -> "application"
        AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "keyboard"
        AccessibilityWindowInfo.TYPE_SYSTEM -> "system"
        AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "accessibility overlay"
        null -> "unknown"
        else -> "type $type"
    }

private fun String.clipped(): String {
    val line = replace('\n', ' ')
    return if (line.length > MAX_TEXT) line.take(MAX_TEXT) + "…" else line
}

/** Enough for the app, a dialog or keyboard over it, and the system bars; the rest is counted. */
private const val MAX_REPORT_LINES = 150

private const val MAX_TEXT = 60
