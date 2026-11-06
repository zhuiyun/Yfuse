package com.yfuse.feature.player

/** Shared by native sidecar discovery and reload; an absent hint remains unknown. */
internal fun externalSubtitleFormatHint(uri: String): String? {
    val normalized = uri.substringBefore('#')
    val path = normalized.substringBefore('?')
    val fileName = path.substringAfterLast('/')
    val extension =
        fileName
            .substringAfterLast('.', missingDelimiterValue = "")
            .trim()
            .lowercase()
            .takeIf { it.matches(Regex("[a-z0-9]{2,8}")) }
    if (extension != null) return extension
    return sequenceOf("format", "codec", "extension")
        .mapNotNull { name ->
            Regex(
                "(?:[?&])${Regex.escape(name)}=([^&]+)",
                RegexOption.IGNORE_CASE,
            ).find(normalized)?.groupValues?.getOrNull(1)
        }.map { it.trim().lowercase() }
        .firstOrNull { it.matches(Regex("[a-z0-9]{2,8}")) }
}
