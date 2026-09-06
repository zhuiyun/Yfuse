package com.yfuse.watch

import java.util.Properties

/** Build provenance for `/watch/version`, so a deployment can be matched to a commit. */
internal object BuildInfo {
    val gitSha: String by lazy {
        System.getenv("GIT_SHA")?.trim()?.takeIf { it.matches(SHA_PATTERN) }
            ?: readResourceSha()
            ?: "unknown"
    }

    private fun readResourceSha(): String? =
        runCatching {
            BuildInfo::class.java.getResourceAsStream("/build-info.properties")?.use { stream ->
                Properties().apply { load(stream) }.getProperty("gitSha")?.trim()
            }
        }.getOrNull()?.takeIf { it.matches(SHA_PATTERN) }

    private val SHA_PATTERN = Regex("[0-9a-f]{7,40}")
}
