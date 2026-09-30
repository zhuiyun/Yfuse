package com.yfuse.di

import com.russhwolf.settings.Settings
import com.yfuse.core.data.TmdbRepository
import com.yfuse.core.filesource.FileSourceClient
import com.yfuse.core.filesource.FileSourceLibraryStore
import com.yfuse.core.filesource.FileSourceProgressStore
import com.yfuse.core.filesource.FileSourceRegistry
import com.yfuse.core.filesource.FileSourceScanner
import com.yfuse.core.filesource.TmdbTitleMatcher
import com.yfuse.core.filesource.createFileSourceClient
import com.yfuse.core.filesource.createFileSourceLibraryStorage
import com.yfuse.core.security.createSecureStore
import org.koin.core.module.Module
import org.koin.dsl.module

/** 文件来源: the WebDAV, SMB and Alist shares, what has been played from them, and their 刮削. */
internal fun fileSourceModule(): Module =
    module {
        // 文件来源: a store of its own rather than entries in ServerRegistry, so nothing that speaks the
        // Emby API is ever handed a WebDAV share. Its passwords get their own Keystore namespace.
        single {
            val persistedSettings = get<Settings>()
            FileSourceRegistry(
                settings = persistedSettings,
                secureStore = createSecureStore(settings = persistedSettings, namespace = "file-source-credentials"),
            )
        }
        single { FileSourceProgressStore(get()) }
        single<FileSourceClient> { createFileSourceClient() }
        // 刮削: the share's paths, named through the same TMDB client as the home page's rows.
        single { FileSourceLibraryStore(createFileSourceLibraryStorage()) }
        single {
            val tmdb = get<TmdbRepository>()
            FileSourceScanner(
                client = get(),
                matcher =
                    TmdbTitleMatcher(
                        search = { query, mediaType, year -> tmdb.searchTitles(query, mediaType, year) },
                        alternativeTitles = tmdb::alternativeTitles,
                    ),
            )
        }
    }
