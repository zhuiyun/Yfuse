package com.yfuse.core.subtitletranslation

import android.content.Context
import com.russhwolf.settings.SharedPreferencesSettings
import com.yfuse.core.security.createSecureStore
import com.yfuse.core.util.androidAppContext
import kotlinx.serialization.json.Json

actual fun createSubtitleTranslationSettingsStore(): SubtitleTranslationSettingsStore =
    AndroidTranslationSettingsStore()

private class AndroidTranslationSettingsStore : SubtitleTranslationSettingsStore {
    private val secure by lazy {
        val context = checkNotNull(androidAppContext)
        createSecureStore(
            SharedPreferencesSettings(context.getSharedPreferences("subtitle-translation", Context.MODE_PRIVATE)),
            "subtitle-translation",
        )
    }

    override fun load(): SubtitleTranslationSettings =
        secure.get("configuration")?.let {
            Json.decodeFromString(SubtitleTranslationSettings.serializer(), it.decodeToString())
        } ?: SubtitleTranslationSettings()

    override fun save(settings: SubtitleTranslationSettings) {
        require(settings.model.isNotBlank() && settings.targetLanguage.isNotBlank())
        translationEndpoint(settings.endpoint)
        require(
            settings.endpoint.length <= 2048 &&
                settings.model.length <= 256 &&
                settings.apiKey.length <= 8192 &&
                settings.targetLanguage.length in 1..80,
        )
        secure.put(
            "configuration",
            Json.encodeToString(SubtitleTranslationSettings.serializer(), settings).encodeToByteArray(),
        )
    }

    override fun clear() {
        secure.remove("configuration")
    }
}
