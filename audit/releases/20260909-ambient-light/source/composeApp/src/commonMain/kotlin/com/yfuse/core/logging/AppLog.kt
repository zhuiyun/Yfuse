package com.yfuse.core.logging

enum class DiagnosticLevel {
    Debug,
    Info,
    Warning,
    Error,
    Critical,
}

/**
 * Structured application diagnostics.
 *
 * Platform implementations are responsible for persistence, retention and export.
 * Callers should use stable, machine-readable category/event names and keep messages
 * free of user content whenever possible.
 */
object AppLog {
    fun debug(
        category: String,
        event: String,
        message: String = "",
        attributes: Map<String, String> = emptyMap(),
    ) = writeDiagnosticLog(
        DiagnosticLevel.Debug,
        category,
        event,
        message,
        null,
        attributes,
    )

    fun info(
        category: String,
        event: String,
        message: String = "",
        attributes: Map<String, String> = emptyMap(),
    ) = writeDiagnosticLog(
        DiagnosticLevel.Info,
        category,
        event,
        message,
        null,
        attributes,
    )

    fun warning(
        category: String,
        event: String,
        message: String = "",
        throwable: Throwable? = null,
        attributes: Map<String, String> = emptyMap(),
    ) = writeDiagnosticLog(
        DiagnosticLevel.Warning,
        category,
        event,
        message,
        throwable,
        attributes,
    )

    fun error(
        category: String,
        event: String,
        message: String = "",
        throwable: Throwable? = null,
        attributes: Map<String, String> = emptyMap(),
    ) = writeDiagnosticLog(
        DiagnosticLevel.Error,
        category,
        event,
        message,
        throwable,
        attributes,
    )
}

internal expect fun writeDiagnosticLog(
    level: DiagnosticLevel,
    category: String,
    event: String,
    message: String,
    throwable: Throwable?,
    attributes: Map<String, String>,
)
