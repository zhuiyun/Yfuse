package com.yfuse.feature.library

import com.arkivanov.mvikotlin.extensions.coroutines.states
import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import com.russhwolf.settings.MapSettings
import com.yfuse.core.data.LibraryCache
import com.yfuse.core.model.SavedServer
import com.yfuse.feature.homeRoutes
import com.yfuse.feature.json
import com.yfuse.feature.testRegistry
import com.yfuse.feature.testRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LibraryStoreTest {

    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test
    fun loads_home_content_for_default_server() = runTest {
        val registry = testRegistry()
        registry.addOrUpdate(SavedServer("id1", "http://host:8096", "我的服务器", "u1", "zhuiyun", "tok"))
        val store = LibraryStoreFactory(
            DefaultStoreFactory(),
            testRepo { homeRoutes(it) },
            registry,
            LibraryCache(MapSettings()),
        ).create()

        val s = store.states.first { !it.loading && !it.content.isEmpty }
        assertEquals("我的服务器", s.currentServer?.serverName)
        assertEquals(1, s.content.resume.size)
        assertTrue(s.content.rows.isNotEmpty())
        store.dispose()
    }

    @Test
    fun no_server_shows_empty() = runTest {
        val store = LibraryStoreFactory(
            DefaultStoreFactory(),
            testRepo { json("{}") },
            testRegistry(),
            LibraryCache(MapSettings()),
        ).create()
        val s = store.states.first()
        assertEquals(null, s.currentServer)
        assertTrue(s.content.isEmpty)
        store.dispose()
    }
}
