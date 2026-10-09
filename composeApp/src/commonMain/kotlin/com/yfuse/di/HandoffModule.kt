package com.yfuse.di

import com.yfuse.app.ProductSession
import com.yfuse.core.cast.CastManager
import com.yfuse.core.cast.createCastManager
import com.yfuse.core.handoff.AccountHandoffApi
import com.yfuse.core.handoff.HandoffController
import com.yfuse.core.handoff.HandoffPlaybackRegistry
import com.yfuse.core.handoff.HandoffVaultCipher
import com.yfuse.core.util.platformName
import com.yfuse.deviceModel
import com.yfuse.feature.player.ActivePlayback
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import org.koin.core.module.Module
import org.koin.dsl.module

/** Playback that moves to another screen: 投屏 over Cast, and 接力 between the account's devices. */
internal fun handoffModule(): Module =
    module {
        single<CastManager> { createCastManager() }
        single { HandoffPlaybackRegistry() }
        single { HandoffVaultCipher(get(), get(), get()) }
        single {
            val session = get<ProductSession>()
            val bridge = get<HandoffPlaybackRegistry>()
            val activeOwner =
                combine(
                    session.owner,
                    session.foreground,
                    ActivePlayback.state,
                ) { owner, foreground, playback ->
                    owner.takeIf { foreground || playback.active }
                }.stateIn(session.scope, SharingStarted.Eagerly, null)
            HandoffController(
                api = get<AccountHandoffApi>(),
                cipher = get<HandoffVaultCipher>(),
                playback = bridge,
                owner = activeOwner,
                scope = session.scope,
                deviceName = deviceModel().take(64),
                platform = platformName(),
                canReceive = {
                    session.foreground.value &&
                        bridge.receiver != null &&
                        !ActivePlayback.state.value.active
                },
            )
        }
    }
