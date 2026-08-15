package com.yfuse.feature.detail

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DetailStoreArchitectureTest {
    @Test
    fun store_factory_keeps_contract_execution_and_reduction_in_focused_files() {
        val factory = projectSource("DetailStore.kt")

        assertTrue("DetailExecutor(" in factory)
        assertTrue("reducer = DetailReducer" in factory)
        assertFalse("sealed interface DetailIntent" in factory)
        assertFalse("class DetailExecutor" in factory)
        assertFalse("fun DetailState.reduce" in factory)

        assertTrue("sealed interface DetailIntent" in projectSource("DetailStoreContract.kt"))
        assertTrue("internal class DetailExecutor" in projectSource("DetailExecutor.kt"))
        assertTrue("internal object DetailReducer" in projectSource("DetailReducer.kt"))
    }

    private fun projectSource(fileName: String): String =
        sequenceOf(
            File("src/commonMain/kotlin/com/yfuse/feature/detail/$fileName"),
            File("composeApp/src/commonMain/kotlin/com/yfuse/feature/detail/$fileName"),
        ).firstOrNull(File::isFile)
            ?.readText()
            ?: error("Cannot locate $fileName from ${File(".").absolutePath}")
}
