package com.yfuse.core.designsystem

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

/** Acquires before scheduling; two taps in one frame cannot launch the same mutation twice. */
internal class ExclusiveUiOperation {
    var label: String? by mutableStateOf(null)
        private set
    val busy: Boolean get() = label != null

    fun launch(
        scope: CoroutineScope,
        message: String,
        block: suspend () -> Unit,
    ): Boolean {
        if (busy) return false
        label = message
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                block()
            } finally {
                label = null
            }
        }
        return true
    }
}
