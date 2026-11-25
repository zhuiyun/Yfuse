package com.yfuse.core.data

import io.ktor.http.Url
import java.text.Normalizer

private val iconAffixes =
    listOf("媒体服务器", "公益服务器", "jellyfin", "服务器", "媒体库", "公益服", "server", "emby", "plex", "logo", "图标", "影院", "影视")

internal fun compactIconName(name: String): String =
    Normalizer.normalize(name, Normalizer.Form.NFKC).lowercase().filter(Char::isLetterOrDigit)

internal fun iconMatchKey(compact: String): String {
    var key = compact
    while (key.isNotEmpty()) {
        val prefix = iconAffixes.firstOrNull(key::startsWith)
        val suffix = iconAffixes.firstOrNull(key::endsWith)
        key =
            when {
                prefix != null -> key.removePrefix(prefix)
                suffix != null -> key.removeSuffix(suffix)
                else -> return key
            }
    }
    return key
}

/** Source order breaks ties; the hostname is only consulted if the display name gives no match. */
internal fun matchServerIcon(
    icons: List<ServerCatalogIcon>,
    serverName: String,
    serverUrl: String,
): ServerCatalogIcon? {
    val spacedName = spacedIconName(serverName)
    val name = spacedName.replace(" ", "")
    val exact = if (name.isEmpty()) null else icons.firstOrNull { it.normalizedName == name }
    val key = iconMatchKey(name)
    if (key.isNotEmpty()) {
        if (exact != null) return exact
        icons.firstOrNull { it.matchKey == key }?.let { return it }
        val phrase = " $spacedName "
        icons
            .asSequence()
            .filter { icon ->
                val part = icon.matchKey
                when {
                    part.length < 2 || part.none(Char::isLetter) -> false
                    part.any { it.code > 127 } -> key.contains(part)
                    else -> part.length >= 3 && (" $part " in phrase || " ${icon.wordName} " in phrase)
                }
            }.maxByOrNull { it.matchKey.length }
            ?.let { return it }
    }
    val host = runCatching { Url(serverUrl).host.lowercase() }.getOrNull() ?: return exact
    val labels =
        host.split('.').dropLast(1).filter {
            it.length >= 2 &&
                it.any(Char::isLetter) &&
                it !in genericHostLabels
        }
    return icons.firstOrNull { it.matchKey in labels } ?: exact
}

internal fun spacedIconName(name: String): String =
    Normalizer
        .normalize(name, Normalizer.Form.NFKC)
        .lowercase()
        .replace(nonNameCharacters, " ")
        .trim()

private val nonNameCharacters = Regex("[^\\p{L}\\p{N}]+")
private val genericHostLabels =
    setOf("www", "api", "cdn", "media", "server", "emby", "plex", "jellyfin", "local", "co", "com", "net", "org")
