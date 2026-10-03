package com.yfuse.di

import com.russhwolf.settings.Settings
import com.yfuse.core.data.DiagnosticPreferences
import com.yfuse.core.personal.PersonalLibraryRepository
import com.yfuse.core.security.VaultCrypto
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * What every other module stands on: the platform's [settings] and [diagnosticPreferences], the
 * vault's crypto, and the personal profile whose policy scopes servers, sync and calendar follows.
 */
internal fun coreModule(
    settings: Settings,
    diagnosticPreferences: DiagnosticPreferences,
): Module =
    module {
        single { settings }
        single { diagnosticPreferences }
        single { VaultCrypto() }
        single { PersonalLibraryRepository(get(), get()) }
    }
