package com.yfuse.watch

import com.yfuse.watch.account.AccountBackend
import com.yfuse.watch.migration.MigrationRelayBackend
import com.yfuse.watch.qoe.QoeAggregateBackend
import com.yfuse.watch.qoe.qoeRoutes
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.routing.routing
import java.io.File

/** Adds the persistent anonymous QoE service around the existing production server module. */
internal fun Application.productionWatchTogetherModule(
    accountBackend: AccountBackend,
    migrationRelayBackend: MigrationRelayBackend,
    requireWatchAuthentication: Boolean,
) {
    val calendarScheduleStore =
        CalendarScheduleStore.sqlite(
            File(System.getenv("CALENDAR_DB_PATH") ?: "/var/lib/yfuse/calendar.db"),
        )
    watchTogetherModule(
        accountBackend = accountBackend,
        migrationRelayBackend = migrationRelayBackend,
        requireWatchAuthentication = requireWatchAuthentication,
        calendarScheduleStore = calendarScheduleStore,
    )
    val backend =
        QoeAggregateBackend.sqlite(
            File(System.getenv("QOE_DB_PATH") ?: "/var/lib/yfuse/qoe.db"),
        )
    monitor.subscribe(ApplicationStopped) { backend.close() }
    val trustProxyHeaders =
        System.getenv("WATCH_TRUST_PROXY_HEADERS")?.equals("true", ignoreCase = true) ?: false
    routing {
        qoeRoutes(
            backend = backend,
            trustProxyHeaders = trustProxyHeaders,
        )
    }
}
