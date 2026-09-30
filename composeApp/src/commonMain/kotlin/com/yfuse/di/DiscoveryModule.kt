package com.yfuse.di

import com.russhwolf.settings.Settings
import com.yfuse.core.data.SearchHistory
import com.yfuse.core.data.TmdbHomeCache
import com.yfuse.core.data.TmdbRepository
import com.yfuse.feature.search.SearchRequests
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * Finding something to watch beyond what the servers list: TMDB, whose last home recommendations
 * are kept in [feedCacheSettings], and search, with its history and the searches other pages ask for.
 */
internal fun discoveryModule(feedCacheSettings: () -> Settings): Module =
    module {
        single { TmdbRepository(get(named("tmdb-http"))) }
        single { TmdbHomeCache(lazy(feedCacheSettings)) }
        single { SearchHistory(get()) }
        single { SearchRequests() }
    }
