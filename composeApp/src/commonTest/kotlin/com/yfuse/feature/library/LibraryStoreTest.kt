package com.yfuse.feature.library

import com.arkivanov.mvikotlin.extensions.coroutines.states
import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import com.yfuse.core.model.SavedServer
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

class LibraryStoreTest {

    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test
    fun loads_default_server_libraries() = runTest {
        val registry = testRegistry()
        registry.addOrUpdate(SavedServer("id1", "http://host:8096", "我的服务器", "u1", "zhuiyun", "tok"))
        val repo = testRepo {
            json(
                """{"Items":[{"Id":"1","Name":"电影","CollectionType":"movies"},""" +
                    """{"Id":"2","Name":"综艺","CollectionType":"tvshows"}]}""",
            )
        }
        val store = LibraryStoreFactory(DefaultStoreFactory(), repo, registry).create()

        val s = store.states.first { it.libraries.isNotEmpty() }
        assertEquals(2, s.libraries.size)
        assertEquals("电影", s.libraries.first().name)
        assertEquals("我的服务器", s.currentServer?.serverName)
        store.dispose()
    }

    @Test
    fun no_server_shows_empty() = runTest {
        val store = LibraryStoreFactory(DefaultStoreFactory(), testRepo { json("{}") }, testRegistry()).create()
        val s = store.states.first()
        assertEquals(null, s.currentServer)
        assertEquals(0, s.libraries.size)
        store.dispose()
    }
}
