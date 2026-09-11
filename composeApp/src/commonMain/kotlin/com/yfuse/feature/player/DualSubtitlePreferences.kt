package com.yfuse.feature.player

enum class DualSubtitleLanguagePair(
    val label: String,
    val primary: Set<String>,
    val secondary: Set<String>,
) {
    ChineseEnglish("中文 / 英文", setOf("zh", "zho", "chi"), setOf("en", "eng")),
    EnglishChinese("英文 / 中文", setOf("en", "eng"), setOf("zh", "zho", "chi")),
    JapaneseChinese("日文 / 中文", setOf("ja", "jpn"), setOf("zh", "zho", "chi")),
}

fun selectDualSubtitleLanguagePair(
    tracks: List<EngineTrack>,
    pair: DualSubtitleLanguagePair,
): Pair<EngineTrack, EngineTrack>? {
    fun EngineTrack.matches(languages: Set<String>): Boolean =
        language?.lowercase()?.substringBefore('-')?.substringBefore('_') in languages
    val primary = tracks.firstOrNull { it.matches(pair.primary) } ?: return null
    val secondary = tracks.firstOrNull { it.id != primary.id && it.matches(pair.secondary) } ?: return null
    return primary to secondary
}
