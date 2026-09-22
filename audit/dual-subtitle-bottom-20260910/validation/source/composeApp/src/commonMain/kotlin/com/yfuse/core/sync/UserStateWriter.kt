package com.yfuse.core.sync

import com.yfuse.core.model.SavedServer

/**
 * The two per-item flags a browsing surface can flip. [ServerSyncManager] is the production
 * implementation and queues each write durably before trying the server, so a failure still
 * means "will reach the server later", never "was lost".
 */
interface UserStateWriter {
    suspend fun setFavorite(
        server: SavedServer,
        itemId: String,
        title: String,
        value: Boolean,
    ): Result<Unit>

    suspend fun setPlayed(
        server: SavedServer,
        itemId: String,
        title: String,
        value: Boolean,
    ): Result<Unit>

    companion object {
        /** Accepts and forgets; for tests and previews that never toggle a flag. */
        val Silent: UserStateWriter =
            object : UserStateWriter {
                override suspend fun setFavorite(
                    server: SavedServer,
                    itemId: String,
                    title: String,
                    value: Boolean,
                ): Result<Unit> = Result.success(Unit)

                override suspend fun setPlayed(
                    server: SavedServer,
                    itemId: String,
                    title: String,
                    value: Boolean,
                ): Result<Unit> = Result.success(Unit)
            }
    }
}
