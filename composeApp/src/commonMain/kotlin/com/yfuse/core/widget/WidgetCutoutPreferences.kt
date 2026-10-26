package com.yfuse.core.widget

import com.russhwolf.settings.Settings
import com.yfuse.core.security.SecureStore
import com.yfuse.core.security.createSecureStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.koin.core.context.GlobalContext

data class WidgetCutoutConfiguration(
    val enabled: Boolean,
    val configured: Boolean,
    val revision: Int = 0,
)

/** Credentials stay in the platform keystore-backed store, outside widget exports and backups. */
class WidgetCutoutPreferences(
    private val settings: Settings,
    private val secrets: SecureStore,
) {
    private val mutableConfiguration =
        MutableStateFlow(
            WidgetCutoutConfiguration(
                enabled = settings.getBoolean("widget.poof.enabled", true),
                configured = apiKey().isNotEmpty(),
            ),
        )
    val configuration = mutableConfiguration.asStateFlow()
    private val mutableStatus = MutableStateFlow("首次联网生成，之后复用本机缓存")
    val status = mutableStatus.asStateFlow()

    fun apiKey(): String = runCatching { secrets.get("poof.api-key")?.decodeToString().orEmpty() }.getOrDefault("")

    fun save(
        enabled: Boolean,
        newKey: String,
    ) {
        val key = newKey.trim()
        require(key.length <= 256 && key.none { it.isWhitespace() || it.isISOControl() }) { "密钥格式不正确" }
        if (key.isNotEmpty()) secrets.put("poof.api-key", key.encodeToByteArray())
        settings.putBoolean("widget.poof.enabled", enabled)
        mutableConfiguration.value =
            WidgetCutoutConfiguration(enabled, apiKey().isNotEmpty(), mutableConfiguration.value.revision + 1)
        mutableStatus.value = if (apiKey().isEmpty()) "请先配置 Poof API Key" else "首次联网生成，之后复用本机缓存"
    }

    fun removeKey() {
        secrets.remove("poof.api-key")
        mutableConfiguration.value =
            mutableConfiguration.value.copy(configured = false, revision = mutableConfiguration.value.revision + 1)
        mutableStatus.value = "请先配置 Poof API Key"
    }

    internal fun report(message: String) {
        mutableStatus.value = message
    }
}

private val processCutoutPreferences by lazy {
    val settings = GlobalContext.get().get<Settings>()
    WidgetCutoutPreferences(settings, createSecureStore(settings, "widget-poof"))
}

fun widgetCutoutPreferences(): WidgetCutoutPreferences = processCutoutPreferences
