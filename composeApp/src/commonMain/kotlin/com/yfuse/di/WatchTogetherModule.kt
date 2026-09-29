package com.yfuse.di

import com.yfuse.core.data.WatchTogetherPreferences
import com.yfuse.core.sync.WatchRoomResumeStore
import com.yfuse.core.sync.WatchTogetherClient
import com.yfuse.feature.watch.WatchInviteResolver
import org.koin.core.module.Module
import org.koin.dsl.module

/** 一起看: the room connection and its preferences, and finding an invite's title on this device's servers. */
internal fun watchTogetherModule(): Module =
    module {
        single { WatchTogetherPreferences(get()) }
        single { WatchTogetherClient(get(), get(), WatchRoomResumeStore(get())) }
        single { WatchInviteResolver(get(), get()) }
    }
