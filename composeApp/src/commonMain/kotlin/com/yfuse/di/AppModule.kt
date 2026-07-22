package com.yfuse.di

import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import com.russhwolf.settings.Settings
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.SessionManager
import com.yfuse.core.network.createEmbyClient
import org.koin.dsl.module

/** Root DI graph. [settings] is provided per platform (SharedPreferences on Android). */
fun appModule(settings: Settings) = module {
    single { settings }
    single { SessionManager(get()) }
    single<StoreFactory> { DefaultStoreFactory() }
    single { createEmbyClient { get<SessionManager>().token() } }
    single { EmbyRepository(get(), get()) }
}
