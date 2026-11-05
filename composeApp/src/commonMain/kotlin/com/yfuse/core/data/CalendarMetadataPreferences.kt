package com.yfuse.core.data

import com.russhwolf.settings.Settings
import com.yfuse.core.security.SecureStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class TvdbCalendarCredentials(
    val apiKey: String,
    val pin: String = "",
)

/** Provider secrets never enter ordinary settings, diagnostics or calendar exports. */
class CalendarMetadataPreferences(
    private val settings: Settings,
    private val secrets: SecureStore,
) {
    var todbEnabled: Boolean
        get() = settings.getBoolean("calendar.metadata.todb.enabled", true)
        set(value) = settings.putBoolean("calendar.metadata.todb.enabled", value)

    fun tvdbCredentials(): TvdbCalendarCredentials? =
        runCatching {
            secrets.get(TVDB_KEY)?.decodeToString()?.let { Json.decodeFromString<TvdbCalendarCredentials>(it) }
        }.getOrNull()

    fun saveTvdb(
        apiKey: String,
        pin: String,
    ) {
        val credentials = TvdbCalendarCredentials(apiKey.trim(), pin.trim())
        require(credentials.apiKey.length in 1..512 && credentials.pin.length <= 128)
        require(credentials.apiKey.none(Char::isWhitespace) && credentials.pin.none(Char::isWhitespace))
        secrets.put(TVDB_KEY, Json.encodeToString(credentials).encodeToByteArray())
    }

    fun clearTvdb() {
        secrets.remove(TVDB_KEY)
    }

    private companion object {
        const val TVDB_KEY = "calendar.tvdb.credentials.v1"
    }
}
