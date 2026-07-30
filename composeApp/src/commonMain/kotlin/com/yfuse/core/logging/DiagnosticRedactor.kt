package com.yfuse.core.logging

private const val Redacted = "<redacted>"

private val sensitiveKeys = setOf(
    "accesstoken",
    "access_token",
    "access-token",
    "apikey",
    "api_key",
    "api-key",
    "authorization",
    "password",
    "pw",
    "token",
    "x-emby-token",
)

private val jsonSecret = Regex(
    """(?i)("(?:access[_-]?token|api[_-]?key|authorization|password|pw|token|x-emby-token)"\s*:\s*")([^"]*)(")""",
)
private val parameterSecret = Regex(
    """(?i)([?&](?:access[_-]?token|api[_-]?key|password|pw|token)=)[^&#\s]+""",
)
private val assignmentSecret = Regex(
    """(?i)(\b(?:access[_-]?token|api[_-]?key|password|pw|token|x-emby-token)\s*[=:]\s*)[^\s,;&}]+""",
)
private val authorizationSecret = Regex(
    """(?i)(\bAuthorization\s*[:=]\s*)[^\r\n,}]+""",
)
private val bearerSecret = Regex("""(?i)(\bBearer\s+)[A-Za-z0-9._~+/=-]+""")
private val urlCredentials = Regex("""(?i)(https?://)[^/@\s]+@""")

internal fun redactDiagnosticText(value: String): String =
    value
        .replace(jsonSecret, "$1$Redacted$3")
        .replace(parameterSecret, "$1$Redacted")
        .replace(authorizationSecret, "$1$Redacted")
        .replace(assignmentSecret, "$1$Redacted")
        .replace(bearerSecret, "$1$Redacted")
        .replace(urlCredentials, "$1$Redacted@")

internal fun redactDiagnosticAttributes(
    attributes: Map<String, String>,
): Map<String, String> = attributes.mapValues { (key, value) ->
    if (key.lowercase() in sensitiveKeys) Redacted else redactDiagnosticText(value)
}
