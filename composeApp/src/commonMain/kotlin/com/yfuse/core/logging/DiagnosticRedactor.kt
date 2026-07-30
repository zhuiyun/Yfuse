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
    "token",
    "x-emby-token",
)

private val jsonSecret = Regex(
    """(?i)("(?:access[_-]?token|api[_-]?key|authorization|client[_-]?secret|cookie|password|pw|refresh[_-]?token|secret|set-cookie|token|x-emby-token)"\s*:\s*")([^"]*)(")""",
)
private val parameterSecret = Regex(
    """(?i)([?&](?:access[_-]?token|api[_-]?key|client[_-]?secret|password|pw|refresh[_-]?token|secret|token)=)[^&#\s]+""",
)
private val assignmentSecret = Regex(
    """(?i)(\b(?:access[_-]?token|api[_-]?key|client[_-]?secret|cookie|password|pw|refresh[_-]?token|secret|set-cookie|token|x-emby-token)\s*[=:]\s*)[^\s,;&}]+""",
)
private val authorizationSecret = Regex(
    """(?i)(\bAuthorization\s*[:=]\s*)[^\r\n,}]+""",
)
private val cookieSecret = Regex(
    """(?i)(\b(?:Cookie|Set-Cookie)\s*:\s*)[^\r\n]+""",
)
private val bearerSecret = Regex("""(?i)(\bBearer\s+)[A-Za-z0-9._~+/=-]+""")
private val urlCredentials = Regex("""(?i)(https?://)[^/@\s]+@""")

internal fun redactDiagnosticText(value: String): String =
    value
        .replace(jsonSecret, "$1$Redacted$3")
        .replace(parameterSecret, "$1$Redacted")
        .replace(authorizationSecret, "$1$Redacted")
        .replace(cookieSecret, "$1$Redacted")
        .replace(assignmentSecret, "$1$Redacted")
        .replace(bearerSecret, "$1$Redacted")
        .replace(urlCredentials, "$1$Redacted@")

internal fun redactDiagnosticAttributes(
    attributes: Map<String, String>,
): Map<String, String> = attributes.mapValues { (key, value) ->
    if (key.lowercase() in sensitiveKeys) Redacted else redactDiagnosticText(value)
}
