package com.yfuse.core.security

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CredentialPersistingSettingsTest {
    @Test
    fun secret_writes_and_removals_commit_while_ordinary_preferences_apply() {
        val calls = mutableListOf<String>()
        val settings = CredentialPersistingSettings(preferences(calls, commitSucceeds = true))
        settings.putString("secure.store.v1.account.token", "encrypted")
        settings.remove("secure.store.v1.account.pending")
        settings.putString("theme", "dark")
        assertEquals(listOf("commit", "commit", "apply"), calls)
    }

    @Test
    fun failed_commit_is_not_reported_as_a_saved_secret() {
        val settings = CredentialPersistingSettings(preferences(mutableListOf(), commitSucceeds = false))
        assertFailsWith<SecureStoreException> { settings.putString("secure.store.v1.account.token", "encrypted") }
        assertFailsWith<SecureStoreException> { settings.remove("secure.store.v1.account.token") }
    }

    private fun preferences(
        calls: MutableList<String>,
        commitSucceeds: Boolean,
    ): SharedPreferences {
        val editor =
            Proxy.newProxyInstance(
                SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java),
            ) { proxy, method, _ ->
                when (method.name) {
                    "putString", "remove" -> proxy
                    "commit" -> {
                        calls += "commit"
                        commitSucceeds
                    }
                    "apply" -> {
                        calls += "apply"
                        null
                    }
                    else -> error("Unexpected editor call: ${method.name}")
                }
            } as SharedPreferences.Editor
        return Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "edit" -> editor
                else -> error("Unexpected preferences call: ${method.name}")
            }
        } as SharedPreferences
    }
}
