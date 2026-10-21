package com.yfuse.di

import com.russhwolf.settings.Settings
import com.yfuse.core.security.createSecureStore
import com.yfuse.core.stremio.StremioClient
import com.yfuse.core.stremio.StremioRepository
import com.yfuse.core.stremio.createStremioHttpClient
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.dsl.onClose

internal fun stremioModule() =
    module {
        single(named("stremio-http")) { createStremioHttpClient() } onClose { it?.close() }
        single { StremioClient(get(named("stremio-http"))) }
        single(named("stremio-secrets")) { createSecureStore(get<Settings>(), "stremio-configurations") }
        factory { parameters -> StremioRepository(get(), get(named("stremio-secrets")), parameters.get()) }
    }
