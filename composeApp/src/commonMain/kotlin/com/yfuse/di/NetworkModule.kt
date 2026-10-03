package com.yfuse.di

import com.yfuse.core.account.ACCOUNT_BASE_URL
import com.yfuse.core.account.AccountAccessTokenSource
import com.yfuse.core.account.createAccountClient
import com.yfuse.core.data.UserAgentPreferences
import com.yfuse.core.network.LanDiscovery
import com.yfuse.core.network.TmdbAccountAccess
import com.yfuse.core.network.createDanmakuClient
import com.yfuse.core.network.createEmbyClient
import com.yfuse.core.network.createLanDiscovery
import com.yfuse.core.network.createTmdbClient
import com.yfuse.core.trakt.createTraktHttpClient
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.dsl.onClose

/**
 * The HTTP clients, one per host the app trusts differently, each closed with the graph.
 *
 * The unqualified client is the media servers'; it reports [appVersion] and the User-Agent chosen
 * in 设置. Every other host has a qualified client of its own: `account-http` (the Yfuse account
 * server), `trakt-http`, `danmaku-http` and `tmdb-http`.
 */
internal fun networkModule(appVersion: String): Module =
    module {
        single(named("account-http")) { createAccountClient() } onClose { it?.close() }
        single(named("trakt-http")) { createTraktHttpClient() } onClose { it?.close() }
        single { UserAgentPreferences(get()) }
        single<LanDiscovery> { createLanDiscovery() }
        single {
            val userAgent = get<UserAgentPreferences>()
            createEmbyClient(
                customUserAgent = { userAgent.userAgent.value },
                appVersion = appVersion,
            )
        } onClose { it?.close() }
        single(named("danmaku-http")) { createDanmakuClient() } onClose { it?.close() }
        // Own client (different host + bearer auth); qualified so the unqualified HttpClient
        // binding stays the Emby one. A signed-in account reads TMDB through the account server's
        // proxy with its own session, the same token source 一起看 and Trakt use.
        single(named("tmdb-http")) {
            val tokens = get<AccountAccessTokenSource>()
            val proxy = "$ACCOUNT_BASE_URL/api/v1/tmdb"
            createTmdbClient(
                account =
                    TmdbAccountAccess(
                        proxyBase = proxy,
                        sessionAvailable = tokens.sessionAvailable,
                        accessToken = { tokens.validAccessTokenFor(proxy) },
                        refreshAccessToken = { tokens.refreshAccessTokenFor(proxy) },
                    ),
            )
        } onClose { it?.close() }
    }
