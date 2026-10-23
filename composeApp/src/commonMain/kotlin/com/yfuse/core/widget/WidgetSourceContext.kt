package com.yfuse.core.widget

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Carries source-specific playback and detail information alongside a resolved film identity. */
@Serializable
data class WidgetSourceContext(
    val widgetId: String,
    val media: WidgetMedia,
    val params: JsonObject = JsonObject(emptyMap()),
)
