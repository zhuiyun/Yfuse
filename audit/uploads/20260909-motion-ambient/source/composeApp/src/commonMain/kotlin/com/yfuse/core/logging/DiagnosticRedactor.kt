package com.yfuse.core.logging

private const val Redacted = "<redacted>"
private const val MaxLogcatMessageChars = 4_000
private const val MaxLogcatAttributeChars = 1_000
private const val MaxLogcatStackTraceChars = 16_000
private const val SENSITIVE_IDENTITY_PATTERN =
    "(?:access[_-]?token|api[_-]?key|authorization|client[_-]?secret|cookie|" +
        "device[_-]?id|domain|host(?:name)?|ip|password|play[_-]?session[_-]?id|pw|" +
        "ray[_-]?id|refresh[_-]?token|secret|server[_-]?id|session[_-]?id|set-cookie|" +
        "token|user[_-]?id|x-emby-token|x-plex-token|zone)"
private const val PUBLIC_DOMAIN_SUFFIX_PATTERN =
    "(?:app|cc|cloud|cn|co|com|dev|example|io|jp|me|net|online|org|site|store|" +
        "tech|test|top|tv|uk|xyz)"

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
        "deviceid",
        "device_id",
        "device-id",
        "domain",
        "host",
        "hostname",
        "ip",
        "password",
        "playsessionid",
        "play_session_id",
        "play-session-id",
        "pw",
        "rayid",
        "ray_id",
        "ray-id",
        "refreshtoken",
        "refresh_token",
        "refresh-token",
        "secret",
        "set-cookie",
        "serverid",
        "server_id",
        "sessionid",
        "session_id",
        "session-id",
        "token",
        "userid",
        "user_id",
        "x-emby-token",
        "x-plex-token",
        "zone",
    )

private val jsonSecret =
    Regex(
        """(?i)("$SENSITIVE_IDENTITY_PATTERN"\s*:\s*")([^"]*)(")""",
    )
private val parameterSecret =
    Regex(
        """(?i)([?&]$SENSITIVE_IDENTITY_PATTERN=)[^&#\s]+""",
    )
private val assignmentSecret =
    Regex(
        """(?i)(\b$SENSITIVE_IDENTITY_PATTERN\s*[=:]\s*)[^\s,;&}]+""",
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
private val embyIdentityPath = Regex("""(?i)(/(?:Users|Items|Sessions|Devices)/)[^/?#\s]+""")
private val ipv4Address =
    Regex("""(?<![\w:])(?:25[0-5]|2[0-4]\d|1?\d?\d)(?:\.(?:25[0-5]|2[0-4]\d|1?\d?\d)){3}(?![\w:])""")
private val ipv6Address =
    Regex(
        """(?ix)(?<![0-9a-f:])(?:
            (?:[0-9a-f]{1,4}:){7}[0-9a-f]{1,4}|
            (?:[0-9a-f]{1,4}:){1,7}:|
            (?:[0-9a-f]{1,4}:){1,6}:[0-9a-f]{1,4}|
            (?:[0-9a-f]{1,4}:){1,5}(?::[0-9a-f]{1,4}){1,2}|
            (?:[0-9a-f]{1,4}:){1,4}(?::[0-9a-f]{1,4}){1,3}|
            (?:[0-9a-f]{1,4}:){1,3}(?::[0-9a-f]{1,4}){1,4}|
            (?:[0-9a-f]{1,4}:){1,2}(?::[0-9a-f]{1,4}){1,5}|
            [0-9a-f]{1,4}:(?:(?::[0-9a-f]{1,4}){1,6})|
            :(?:(?::[0-9a-f]{1,4}){1,7}|:)
        )(?![0-9a-f:])""",
    )
private val plainDomain =
    Regex(
        """(?i)(?<![\w@])(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+""" +
            """$PUBLIC_DOMAIN_SUFFIX_PATTERN(?::\d{1,5})?(?![\w-])""",
    )

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
        .replace(embyIdentityPath, "$1$Redacted")
        .replace(ipv4Address, "<redacted-ip>")
        .replace(ipv6Address, "<redacted-ip>")
        .replace(plainDomain, "<redacted-host>")

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
