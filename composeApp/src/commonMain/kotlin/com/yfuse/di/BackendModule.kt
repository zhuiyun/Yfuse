package com.yfuse.di

import com.yfuse.backend.BackendAccess
import com.yfuse.backend.BackendDiagnostics
import com.yfuse.backend.CalendarBackendApi
import com.yfuse.backend.HttpCalendarBackendApi
import com.yfuse.backend.HttpQoeBackendApi
import com.yfuse.backend.QoeBackendApi
import com.yfuse.core.account.AccountAccessTokenSource
import com.yfuse.core.account.AccountApi
import com.yfuse.core.account.PlaybackCloudApi
import com.yfuse.core.account.createAccountClient
import com.yfuse.core.handoff.AccountHandoffApi
import com.yfuse.core.logging.AppLog
import com.yfuse.core.trakt.AccountTraktAuthApi
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.dsl.onClose

/** Single assembly point for the optional first-party service transports. */
fun backendModule(access: BackendAccess = BackendAccess.Default) =
    module {
        single { access }
        single(named("account-http")) { createAccountClient(access = get()) } onClose { it?.close() }
        single<BackendDiagnostics> {
            object : BackendDiagnostics {
                override fun info(
                    event: String,
                    message: String,
                    attributes: Map<String, String>,
                ) {
                    AppLog.info(category = "account", event = event, message = message, attributes = attributes)
                }

                override fun warning(
                    event: String,
                    message: String,
                    attributes: Map<String, String>,
                ) {
                    AppLog.warning(category = "account", event = event, message = message, attributes = attributes)
                }
            }
        }
        single { AccountAccessTokenSource(backendAccess = get()) }
        single { AccountApi(get(named("account-http")), backendAccess = get(), diagnostics = get()) }
        single { PlaybackCloudApi(get(named("account-http")), backendAccess = get()) }
        single { AccountHandoffApi(get(named("account-http")), get(), access = get()) }
        single { AccountTraktAuthApi(get(named("account-http")), get(), access = get()) }
        single<CalendarBackendApi> { HttpCalendarBackendApi(get(named("account-http")), access = get()) }
        single<QoeBackendApi> { HttpQoeBackendApi(get(named("account-http")), access = get()) }
    }
