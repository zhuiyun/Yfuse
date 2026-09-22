package com.yfuse.tv.focus

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FocusRepositoryTest {
    @Test
    fun recordsLastItemGloballyAndPerSection() {
        val repository = InMemoryFocusRepository()
        val context = FocusContext(route = "home", serverId = "emby", profileId = "adult")
        val hero = anchor(context, section = "hero", item = "movie-1", index = 0)
        val continueWatching =
            anchor(context, section = "continue", item = "episode-8", index = 3, offset = 24)

        repository.record(hero)
        repository.record(continueWatching)

        assertEquals(continueWatching, repository.last(context))
        assertEquals(hero, repository.lastInSection(context, "hero"))
        assertEquals(continueWatching, repository.lastInSection(context, "continue"))
    }

    @Test
    fun separatesServersAndProfilesAndCanRemoveOneServer() {
        val repository = InMemoryFocusRepository()
        val first = FocusContext("library", "emby-a", "alice")
        val second = FocusContext("library", "emby-a", "bob")
        val third = FocusContext("library", "plex-b", "alice")
        repository.record(anchor(first, "movies", "a", 0))
        repository.record(anchor(second, "movies", "b", 0))
        repository.record(anchor(third, "movies", "c", 0))

        repository.removeServer("emby-a")

        assertNull(repository.last(first))
        assertNull(repository.last(second))
        assertEquals("c", repository.last(third)?.itemStableId)
    }

    @Test
    fun evictsLeastRecentlyUsedContext() {
        val repository = InMemoryFocusRepository(maxContexts = 2)
        val first = FocusContext("home", "one")
        val second = FocusContext("home", "two")
        val third = FocusContext("home", "three")
        repository.record(anchor(first, "row", "one", 0))
        repository.record(anchor(second, "row", "two", 0))
        // Reading first makes second the least recently used entry.
        repository.last(first)
        repository.record(anchor(third, "row", "three", 0))

        assertEquals("one", repository.last(first)?.itemStableId)
        assertNull(repository.last(second))
        assertEquals("three", repository.last(third)?.itemStableId)
    }

    @Test
    fun limitsRememberedSectionsWithoutDroppingCurrentAnchor() {
        val repository = InMemoryFocusRepository(maxSectionsPerContext = 2)
        val context = FocusContext("home")
        repository.record(anchor(context, "one", "1", 0))
        repository.record(anchor(context, "two", "2", 0))
        repository.record(anchor(context, "three", "3", 0))

        assertNull(repository.lastInSection(context, "one"))
        assertEquals("2", repository.lastInSection(context, "two")?.itemStableId)
        assertEquals("3", repository.last(context)?.itemStableId)
    }

    private fun anchor(
        context: FocusContext,
        section: String,
        item: String,
        index: Int,
        offset: Int = 0,
    ) = FocusAnchor(
        route = context.route,
        serverId = context.serverId,
        profileId = context.profileId,
        sectionId = section,
        itemStableId = item,
        fallbackIndex = index,
        scrollOffset = offset,
    )
}
