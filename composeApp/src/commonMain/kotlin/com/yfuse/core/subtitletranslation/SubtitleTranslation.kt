package com.yfuse.core.subtitletranslation

import kotlinx.serialization.Serializable

@Serializable
data class SubtitleTranslationSettings(
    val endpoint: String = "",
    val model: String = "",
    val apiKey: String = "",
    val targetLanguage: String = "简体中文",
) {
    override fun toString(): String = "SubtitleTranslationSettings([redacted])"
}

interface SubtitleTranslationSettingsStore {
    fun load(): SubtitleTranslationSettings

    fun save(settings: SubtitleTranslationSettings)

    fun clear()
}

expect fun createSubtitleTranslationSettingsStore(): SubtitleTranslationSettingsStore
