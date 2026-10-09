package com.yfuse.core.network

import com.yfuse.backend.BackendAccess
import com.yfuse.core.logging.AppLog

/** Application diagnostics adapter for the removable account-server TMDB route. */
internal fun createTmdbRequestRouter(
    builtInToken: () -> String,
    account: TmdbAccountAccess?,
    nowEpochMs: () -> Long,
    backendAccess: BackendAccess,
): TmdbRequestRouter =
    TmdbRequestRouter(builtInToken, account, nowEpochMs, backendAccess) { event ->
        if (event.warning) {
            AppLog.warning(
                category = "tmdb",
                event = event.event,
                message = event.message,
                attributes = event.attributes,
            )
        } else {
            AppLog.info(
                category = "tmdb",
                event = event.event,
                message = event.message,
                attributes = event.attributes,
            )
        }
    }
