package com.yfuse.core.sync

import com.yfuse.core.model.SavedServer
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UserStateWriterTest {
    private val server = SavedServer("one", "http://host:8096", "Media", "u1", "viewer", "token")

    @Test
    fun the_silent_writer_accepts_every_flag_change_without_side_effects() =
        runTest {
            listOf(true, false).forEach { value ->
                assertTrue(UserStateWriter.Silent.setFavorite(server, "item-1", "Title", value).isSuccess)
                assertTrue(UserStateWriter.Silent.setPlayed(server, "item-1", "Title", value).isSuccess)
            }
        }

    @Test
    fun an_implementation_receives_the_exact_target_and_flag_and_may_report_a_failure() =
        runTest {
            val writes = mutableListOf<String>()
            val writer =
                object : UserStateWriter {
                    override suspend fun setFavorite(
                        server: SavedServer,
                        itemId: String,
                        title: String,
                        value: Boolean,
                    ): Result<Unit> = record("favorite", server, itemId, title, value)

                    override suspend fun setPlayed(
                        server: SavedServer,
                        itemId: String,
                        title: String,
                        value: Boolean,
                    ): Result<Unit> = record("played", server, itemId, title, value)

                    private fun record(
                        flag: String,
                        server: SavedServer,
                        itemId: String,
                        title: String,
                        value: Boolean,
                    ): Result<Unit> {
                        if (itemId.isBlank()) return Result.failure(IllegalArgumentException("blank item id"))
                        writes += "${server.id}/$itemId/$title/$flag=$value"
                        return Result.success(Unit)
                    }
                }

            assertTrue(writer.setFavorite(server, "item-1", "The Matrix", true).isSuccess)
            assertTrue(writer.setPlayed(server, "item-2", "Reloaded", false).isSuccess)
            assertTrue(writer.setPlayed(server, "", "Blank", true).isFailure)

            assertEquals(
                listOf("one/item-1/The Matrix/favorite=true", "one/item-2/Reloaded/played=false"),
                writes,
            )
        }
}
