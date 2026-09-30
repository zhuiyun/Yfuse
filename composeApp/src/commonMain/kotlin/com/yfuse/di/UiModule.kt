package com.yfuse.di

import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import com.yfuse.core.data.HomeShelfPreferences
import com.yfuse.core.data.ThemePreferences
import com.yfuse.core.data.TipsPreferences
import com.yfuse.feature.library.LibraryGridColumnsPreferences
import org.koin.core.module.Module
import org.koin.dsl.module

/** What the screens share: MVIKotlin's store factory, and the appearance, tips and layout preferences. */
internal fun uiModule(): Module =
    module {
        single<StoreFactory> { DefaultStoreFactory() }
        single { ThemePreferences(get()) }
        single { TipsPreferences(get()) }
        single { HomeShelfPreferences(get()) }
        single { LibraryGridColumnsPreferences(get()) }
    }
