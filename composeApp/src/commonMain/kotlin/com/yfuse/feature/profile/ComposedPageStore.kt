package com.yfuse.feature.profile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import com.arkivanov.mvikotlin.core.store.Store

/**
 * A settings page's store for a surface that composes the page without [ProfileComponent]'s stack:
 * the television's settings, which swap their pages in place. Made with the page and disposed as it
 * leaves, as the page's own state always was there; on the phone the stack's child owns the store.
 */
@Composable
internal fun <S : Store<*, *, *>> rememberComposedPageStore(
    vararg keys: Any?,
    create: () -> S,
): S {
    val store = remember(*keys) { create() }
    DisposableEffect(store) {
        onDispose { store.dispose() }
    }
    return store
}
