package com.yfuse.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import com.yfuse.core.data.TipsPreferences
import com.yfuse.core.designsystem.LocalTips
import com.yfuse.core.designsystem.TipsState
import com.yfuse.core.util.currentIsoDate
import org.koin.core.context.GlobalContext

/**
 * The app's 情境提示, backed by the one [TipsPreferences]. The shell and the player are separate
 * activities; they read the same store, so the day's one tip is shared between them too.
 */
@Composable
fun rememberAppTips(): TipsState? =
    remember {
        runCatching { GlobalContext.get().getOrNull<TipsPreferences>() }
            .getOrNull()
            ?.let { store -> TipsState(store) { currentIsoDate() } }
    }

/** Provides [rememberAppTips] to [content]; for roots other than the shell's own. */
@Composable
fun ProvideAppTips(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalTips provides rememberAppTips(), content = content)
}
