package com.yfuse.di

import com.yfuse.app.ProductSession
import com.yfuse.core.account.AccountAccessTokenSource
import com.yfuse.core.account.AccountApi
import com.yfuse.core.account.AccountRepository
import com.yfuse.core.account.AccountState
import com.yfuse.core.account.PersonalAutoSync
import com.yfuse.core.account.PlaybackCloudApi
import com.yfuse.core.account.PlaybackVaultCipher
import com.yfuse.core.data.PlaybackProgressProjection
import com.yfuse.core.personal.PersonalLibraryRepository
import com.yfuse.core.security.SecureStore
import com.yfuse.core.security.createSecureStore
import com.yfuse.core.sync.ProgressSyncPreferences
import com.yfuse.core.sync.ServerSyncManager
import com.yfuse.core.sync.playback.PlaybackSyncManager
import com.yfuse.core.sync.playback.PlaybackSyncStore
import com.yfuse.core.trakt.AccountTraktAuthApi
import com.yfuse.core.trakt.HttpTraktApi
import com.yfuse.core.trakt.PersonalTraktImportSink
import com.yfuse.core.trakt.TraktRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.dsl.onClose

/**
 * The Yfuse account and what it keeps in step: the session and the secure store its vault key and
 * tokens live in, the product session account-scoped integrations such as Trakt run in, 个人内容
 * merged with the account's end-to-end encrypted cloud, and watch progress, stored here, projected
 * over what the servers report, and synced with the servers and with that cloud.
 */
internal fun accountModule(): Module =
    module {
        single<SecureStore> { createSecureStore(get(), namespace = "account") }
        single { AccountAccessTokenSource() }
        single { AccountApi(get(named("account-http"))) }
        single {
            AccountRepository(
                api = get(),
                secureStore = get(),
                crypto = get(),
                registry = get(),
                theme = get(),
                userAgent = get(),
                watch = get(),
                danmaku = get(),
                skip = get(),
                serverSync = get(),
                calendarFollows = get(),
                accessTokenSource = get(),
                mutationDispatcher = Dispatchers.Main.immediate,
                personal = get(),
            )
        }
        single { ProductSession(get(), get()) }
        single {
            val account = get<AccountRepository>()
            PersonalAutoSync(
                signedInUser = account.state.map { (it as? AccountState.SignedIn)?.session?.user?.id },
                changes = get<PersonalLibraryRepository>().contentRevision,
                foreground = get<ProductSession>().foreground,
                merge = account::syncPersonalAutomatically,
            )
        }
        single {
            val session = get<ProductSession>()
            TraktRepository(
                api = HttpTraktApi(get(named("trakt-http"))),
                auth = AccountTraktAuthApi(get(named("account-http")), get()),
                secureStore = get(),
                owner = session.owner,
                importSink = PersonalTraktImportSink(get()),
                scope = session.scope,
            ).also { it.start() }
        } onClose { it?.close() }
        single { ProgressSyncPreferences(get()) }
        single { PlaybackSyncStore(get(), personal = get()) }
        single {
            val progressPreferences = get<ProgressSyncPreferences>()
            PlaybackProgressProjection(
                localStore = get(),
                progressSyncEnabled = { progressPreferences.enabled.value },
            )
        }
        single { ServerSyncManager(get(), get(), get(), get(), get()) }
        single { PlaybackCloudApi(get(named("account-http"))) }
        single { PlaybackVaultCipher(get(), get(), get()) }
        single {
            PlaybackSyncManager(
                store = get(),
                cloud = get(),
                cipher = get(),
                accessTokens = get(),
                repo = get(),
                registry = get(),
                progressSyncEnabled = get<ServerSyncManager>().syncProgress,
                personal = get(),
                // Skips servers the monitor has marked as needing re-login or backing off, and
                // persists the per-server apply backoff across restarts (see PlaybackSyncManager).
                serverHealth = get(),
                settings = get(),
            )
        }
    }
