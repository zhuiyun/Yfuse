package com.yfuse.core.playback

/** Header values must never appear in queue diagnostics. */
class PlayerCustomHeaders(
    values: Map<String, String>,
) {
    val values: Map<String, String> =
        values.toMap().also { headers ->
            require(headers.size <= 32)
            headers.forEach { (name, value) ->
                require(name.matches(Regex("[A-Za-z0-9!#$%&'*+.^_`|~-]+")))
                require(name.lowercase() !in setOf("host", "connection", "content-length"))
                require(value.length <= 8192 && value.none { it == '\r' || it == '\n' || it == '\u0000' })
            }
        }

    override fun toString(): String = "PlayerCustomHeaders([redacted])"
}
