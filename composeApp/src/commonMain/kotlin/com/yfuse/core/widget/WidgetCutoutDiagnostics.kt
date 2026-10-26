package com.yfuse.core.widget

import com.yfuse.core.logging.playbackDiagnosticTrace

/** Only anonymous correlation references and explicitly chosen measurements enter diagnostics. */
internal fun logWidgetCutout(
    event: String,
    source: String? = null,
    attributes: Map<String, String> = emptyMap(),
    warning: Boolean = false,
) {
    val reference = source?.takeIf(String::isNotBlank)?.let(::playbackDiagnosticTrace)
    recordWidgetCutoutDiagnostic(
        event,
        attributes + listOfNotNull(reference?.let { "sourceRef" to it }).toMap(),
        warning,
    )
}

internal expect fun recordWidgetCutoutDiagnostic(
    event: String,
    attributes: Map<String, String>,
    warning: Boolean,
)

/** Never records exception messages, request headers, source URLs or response bodies. */
internal expect fun widgetCutoutFailureAttributes(failure: Throwable): Map<String, String>
