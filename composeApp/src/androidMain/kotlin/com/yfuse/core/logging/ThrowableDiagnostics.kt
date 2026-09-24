package com.yfuse.core.logging

import java.util.Collections
import java.util.IdentityHashMap

/**
 * Failure fields for exported diagnostics that stay meaningful in release builds.
 *
 * R8 keeps the class names of throwables (see proguard-rules.pro) but renames the app classes
 * that throw them and moves them out of `com.yfuse`. The old origin, "first `com.yfuse.` frame",
 * therefore matched nothing and every 1.0.83 failure logged `origin=` empty. The origin is now the
 * first frame outside the platform and runtime libraries: in a release build that is the renamed
 * `Class.method:line`, which the archived mapping file of the same build retraces.
 */
internal fun Throwable.diagnosticRootCause(): Throwable {
    val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    var current = this
    while (seen.add(current)) {
        current = current.cause ?: return current
    }
    return current
}

/** Simple class name of this throwable; readable in release builds because names are kept. */
internal fun Throwable.diagnosticTypeName(): String = javaClass.simpleName.ifBlank { javaClass.name }

/** `Class.method:line` of the first non-library frame, or of the first frame when all are library. */
internal fun Throwable.diagnosticOrigin(): String {
    val trace = stackTrace
    val frame = trace.firstOrNull { !it.className.isLibraryFrame() } ?: trace.firstOrNull() ?: return ""
    return "${frame.className.substringAfterLast('.')}.${frame.methodName}:${frame.lineNumber}"
}

private fun String.isLibraryFrame(): Boolean = LIBRARY_FRAME_PREFIXES.any(::startsWith)

private val LIBRARY_FRAME_PREFIXES =
    listOf(
        "java.",
        "javax.",
        "jdk.",
        "sun.",
        "kotlin.",
        "kotlinx.",
        "android.",
        "androidx.",
        "com.android.",
        "dalvik.",
        "libcore.",
        "io.ktor.",
        "okhttp3.",
        "okio.",
        "org.chromium.",
    )
