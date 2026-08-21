package com.yfuse.core.logging

private const val Redacted = "<redacted>"
private const val MaxLogcatMessageChars = 4_000
private const val MaxLogcatAttributeChars = 1_000
private const val MaxLogcatStackTraceChars = 16_000

private val sensitiveKeys =
    setOf(
        "accesstoken",
        "access_token",
        "access-token",
        "apikey",
        "api_key",
        "api-key",
        "authorization",
        "clientsecret",
        "client_secret",
        "client-secret",
        "cookie",
        "password",
        "pw",
        "refreshtoken",
        "refresh_token",
        "refresh-token",
        "secret",
        "set-cookie",
        "serverid",
        "server_id",
        "token",
        "userid",
        "user_id",
        "x-emby-token",
    )

private val jsonSecret =
    Regex(
        """(?i)("(?:access[_-]?token|api[_-]?key|authorization|client[_-]?secret|cookie|password|pw|refresh[_-]?token|secret|server[_-]?id|set-cookie|token|user[_-]?id|x-emby-token)"\s*:\s*")([^"]*)(")""",
    )
private val parameterSecret =
    Regex(
        """(?i)([?&](?:access[_-]?token|api[_-]?key|client[_-]?secret|password|pw|refresh[_-]?token|secret|token)=)[^&#\s]+""",
    )
private val assignmentSecret =
    Regex(
        """(?i)(\b(?:access[_-]?token|api[_-]?key|client[_-]?secret|cookie|password|pw|refresh[_-]?token|secret|set-cookie|token|x-emby-token)\s*[=:]\s*)[^\s,;&}]+""",
    )
private val authorizationSecret =
    Regex(
        """(?i)(\bAuthorization\s*[:=]\s*)[^\r\n,}]+""",
    )
private val cookieSecret =
    Regex(
        """(?i)(\b(?:Cookie|Set-Cookie)\s*:\s*)[^\r\n]+""",
    )
private val bearerSecret = Regex("""(?i)(\bBearer\s+)[A-Za-z0-9._~+/=-]+""")
private val urlCredentials = Regex("""(?i)(https?://)[^/@\s]+@""")
private val urlAuthority = Regex("""(?i)\b(https?://)[^/\s?#]+""")
private val embyUserPath = Regex("""(?i)(/Users/)[^/?#\s]+""")

internal fun redactDiagnosticText(value: String): String =
    value
        .replace(jsonSecret, "$1$Redacted$3")
        .replace(parameterSecret, "$1$Redacted")
        .replace(authorizationSecret, "$1$Redacted")
        .replace(cookieSecret, "$1$Redacted")
        .replace(assignmentSecret, "$1$Redacted")
        .replace(bearerSecret, "$1$Redacted")
        .replace(urlCredentials, "$1$Redacted@")
        .replace(urlAuthority, "$1<redacted-host>")
        .replace(embyUserPath, "$1$Redacted")

internal fun redactDiagnosticAttributes(attributes: Map<String, String>): Map<String, String> =
    attributes.mapValues { (key, value) ->
        if (key.lowercase() in sensitiveKeys) Redacted else redactDiagnosticText(value)
    }

/**
 * Produces the only payload Android diagnostics are allowed to send to Logcat.
 * Redaction happens before truncation so a truncated replacement can never reveal a prefix.
 */
internal fun formatSafeLogcatMessage(
    event: String? = null,
    message: String,
    attributes: Map<String, String> = emptyMap(),
    throwableText: String? = null,
): String =
    buildString {
        event?.let {
            append(redactDiagnosticText(it).take(120))
            append(" | ")
        }
        append(redactDiagnosticText(message).take(MaxLogcatMessageChars))
        redactDiagnosticAttributes(attributes)
            .entries
            .take(32)
            .takeIf { it.isNotEmpty() }
            ?.let { entries ->
                append(' ')
                append(
                    entries.joinToString(", ") { (key, value) ->
                        "${redactDiagnosticText(key).take(80)}=${value.take(MaxLogcatAttributeChars)}"
                    },
                )
            }
        throwableText?.takeIf { it.isNotBlank() }?.let {
            append('\n')
            append(redactDiagnosticText(it).take(MaxLogcatStackTraceChars))
        }
    }
