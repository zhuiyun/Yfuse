package com.yfuse.feature.widgets

import androidx.compose.runtime.saveable.Saver
import com.yfuse.core.widget.widgetJson
import kotlinx.serialization.encodeToString

/** Only small page drafts enter saved instance state; JS source stays out of the activity Bundle. */
internal inline fun <reified T> widgetDraftSaver(): Saver<T, String> =
    Saver(
        save = { widgetJson.encodeToString(it) },
        restore = { widgetJson.decodeFromString<T>(it) },
    )

internal class WidgetBackRouter(
    var onBack: () -> Unit,
)
