package com.yfuse.core.security

import android.content.SharedPreferences
import com.russhwolf.settings.Settings
import com.russhwolf.settings.SharedPreferencesSettings

/** Secret writes must survive process termination before a rotated token is acknowledged locally. */
internal class CredentialPersistingSettings(
    private val preferences: SharedPreferences,
) : Settings by SharedPreferencesSettings(preferences) {
    override fun putString(
        key: String,
        value: String,
    ) {
        val editor = preferences.edit().putString(key, value)
        persist(key, editor)
    }

    override fun remove(key: String) {
        persist(key, preferences.edit().remove(key))
    }

    private fun persist(
        key: String,
        editor: SharedPreferences.Editor,
    ) {
        if (key.startsWith("secure.store.v1.")) {
            if (!editor.commit()) throw SecureStoreException("Credential storage could not be committed")
        } else {
            editor.apply()
        }
    }
}
