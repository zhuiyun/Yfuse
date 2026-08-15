package com.yfuse.core.data

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EmbyRepositoryArchitectureTest {
    @Test
    fun repository_is_a_compatibility_facade_for_focused_services() {
        val repository = projectSource("EmbyRepository.kt")

        listOf(
            "EmbyAuthService",
            "EmbyBrowseService",
            "EmbyDetailService",
            "EmbyHomeService",
            "EmbyLibraryService",
            "EmbyLookupService",
            "EmbyPlaybackService",
            "EmbySearchService",
            "EmbyServerService",
            "EmbySourceService",
            "EmbySubtitleService",
            "EmbyUserDataService",
        ).forEach { service ->
            assertTrue(service in repository, "$service must remain behind the repository facade")
            assertTrue(projectSource("$service.kt").isNotBlank())
        }

        assertFalse("/Users/AuthenticateByName" in repository)
        assertFalse("/Sessions/Playing" in repository)
        assertFalse("/RemoteSearch/Subtitles/" in repository)
        assertFalse("client.get(" in repository)
    }

    private fun projectSource(fileName: String): String =
        sequenceOf(
            File("src/commonMain/kotlin/com/yfuse/core/data/$fileName"),
            File("composeApp/src/commonMain/kotlin/com/yfuse/core/data/$fileName"),
        ).firstOrNull(File::isFile)
            ?.readText()
            ?: error("Cannot locate $fileName from ${File(".").absolutePath}")
}
