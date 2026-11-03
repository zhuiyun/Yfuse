package com.yfuse.core.logging

/** Diagnostics are outside this host suite; execution and timing use production implementations. */
object AppLog {
    fun info(category: String, event: String, message: String, attributes: Map<String, String> = emptyMap()) = Unit
    fun warning(category: String, event: String, message: String, attributes: Map<String, String> = emptyMap(), throwable: Throwable? = null) = Unit
    fun error(category: String, event: String, message: String, throwable: Throwable? = null, attributes: Map<String, String> = emptyMap()) = Unit
}
fun playbackDiagnosticTrace(value: String?): String = "host-regression"
fun Throwable.diagnosticRootCause(): Throwable = this
fun Throwable.diagnosticTypeName(): String = javaClass.simpleName
fun Throwable.diagnosticOrigin(): String = "host-regression"
