package com.yfuse.core.migration

import com.yfuse.backend.BackendAccess
import io.ktor.client.engine.HttpClientEngine

/** A short-lived engine whose connection pool belongs exclusively to one migration client. */
internal expect fun migrationRelayHttpEngine(): HttpClientEngine

/** Platform composition: backend code never creates or borrows a media engine. */
fun createMigrationRelayApi(access: BackendAccess = BackendAccess.Default): MigrationRelayApi {
    val engine = migrationRelayHttpEngine()
    return try {
        MigrationRelayApi(
            client = createMigrationRelayClient(engine, access = access),
            access = access,
            ownedEngine = engine,
        )
    } catch (failure: Throwable) {
        engine.close()
        throw failure
    }
}
