package com.yfuse.watch

import java.time.Instant

/**
 * One-line structured log records on stderr, which the service unit hands to the journal.
 *
 * Values are flattened to `key=value` with control characters removed so a record can never
 * span lines or smuggle terminal escapes; secrets are the caller's responsibility to omit.
 */
internal object ServerLog {
    fun info(
        event: String,
        vararg fields: Pair<String, Any?>,
    ) = write("INFO", event, fields, throwable = null)

    fun warn(
        event: String,
        vararg fields: Pair<String, Any?>,
        throwable: Throwable? = null,
    ) = write("WARN", event, fields, throwable)

    fun error(
        event: String,
        vararg fields: Pair<String, Any?>,
        throwable: Throwable? = null,
    ) = write("ERROR", event, fields, throwable)

    private fun write(
        level: String,
        event: String,
        fields: Array<out Pair<String, Any?>>,
        throwable: Throwable?,
    ) {
        val line =
            buildString {
                append(Instant.now())
                append(' ')
                append(level)
                append(' ')
                append(sanitize(event))
                fields.forEach { (key, value) ->
                    if (value == null) return@forEach
                    append(' ')
                    append(sanitize(key))
                    append('=')
                    append(quote(sanitize(value.toString())))
                }
                if (throwable != null) {
                    append(" error=")
                    append(quote(sanitize("${throwable::class.simpleName}: ${throwable.message.orEmpty()}")))
                }
            }
        System.err.println(line)
    }

    private fun sanitize(value: String): String =
        value
            .replace(Regex("[\\p{Cntrl}]"), " ")
            .take(MAX_FIELD_CHARS)

    private fun quote(value: String): String {
        val needsQuotes = value.any { it == ' ' || it == '"' }
        return if (needsQuotes) "\"${value.replace("\"", "'")}\"" else value
    }

    private const val MAX_FIELD_CHARS = 400
}
