package com.yfuse.feature.widgets

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yfuse.app.AppTheme
import com.yfuse.core.data.ThemePreferences
import com.yfuse.core.designsystem.AppBackdrop
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.DialogBackdropHost
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.widget.downloadWidgetSource
import com.yfuse.core.widget.readWidgetFile
import com.yfuse.core.widget.widgetRepository
import kotlinx.coroutines.CancellationException
import org.koin.core.context.GlobalContext
import com.yfuse.core.designsystem.ThemeText as Text

/** Validates the payload again, including explicit intents which bypass manifest filtering. */
class WidgetImportActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val incoming = intent
        setContent {
            AppTheme(GlobalContext.get().get<ThemePreferences>()) {
                var source by remember { mutableStateOf<Pair<String, String>?>(null) }
                var error by remember { mutableStateOf<String?>(null) }
                var attempt by remember { mutableIntStateOf(0) }
                LaunchedEffect(attempt) {
                    error = null
                    try {
                        source = readIncoming(incoming)
                    } catch (
                        e: CancellationException,
                    ) {
                        throw e
                    } catch (e: Exception) {
                        error = e.message ?: "无法读取组件文件"
                    }
                }
                DialogBackdropHost {
                    AppBackdrop {
                        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                            if (source != null) {
                                WidgetWorkspaceScreen(
                                    widgetRepository(),
                                    onClose = ::openHome,
                                    initialSource = source,
                                    embedded = true,
                                )
                            } else {
                                WidgetFrame("安装组件", ::finish) {
                                    Column(
                                        Modifier
                                            .weight(
                                                1f,
                                            ).fillMaxWidth()
                                            .verticalScroll(rememberScrollState())
                                            .padding(24.dp),
                                        verticalArrangement = Arrangement.spacedBy(16.dp),
                                    ) {
                                        if (error == null) {
                                            WidgetProgress(Modifier.fillMaxWidth())
                                            Text(
                                                "正在读取组件…",
                                                color = LocalPalette.current.text,
                                                style = AppTypography.body.medium,
                                            )
                                            Text(
                                                "读取完成后，可以先查看信息再安装。",
                                                color = LocalPalette.current.sub2,
                                                style = AppTypography.body.reading,
                                            )
                                        } else {
                                            WidgetEmptyState(AppIcons.Info, "无法读取组件", "请检查文件或链接后重试。", "重新读取") {
                                                attempt++
                                            }
                                            Text(
                                                error.orEmpty().take(2000),
                                                color = LocalPalette.current.error,
                                                style = AppTypography.body.reading,
                                            )
                                            WidgetSecondaryButton(
                                                ::openHome,
                                                Modifier.fillMaxWidth(),
                                            ) { Text("打开 Yfuse") }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun readIncoming(incoming: Intent): Pair<String, String> {
        val data = incoming.data
        if (incoming.action == Intent.ACTION_VIEW && data?.scheme == "yfuse" && data.host == "add-widget") {
            val url = requireNotNull(data.getQueryParameter("url")) { "安装链接缺少 url 参数" }
            return downloadWidgetSource(url) to url
        }
        require(incoming.action in setOf(Intent.ACTION_VIEW, Intent.ACTION_SEND)) { "不支持此打开方式" }
        @Suppress("DEPRECATION")
        val uri: Uri =
            when (incoming.action) {
                Intent.ACTION_VIEW -> data
                else ->
                    incoming.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                        ?: incoming.clipData
                            ?.takeIf { it.itemCount > 0 }
                            ?.getItemAt(0)
                            ?.uri
            } ?: error("未收到 JS 文件，请通过文件分享或在 Yfuse 中导入")
        if (uri.scheme in setOf("https", "http")) {
            require(uri.lastPathSegment?.endsWith(".js", true) == true) { "请选择 JS 组件链接" }
            return downloadWidgetSource(uri.toString()) to uri.toString()
        }
        val (text, name) = readWidgetFile(this, uri)
        require(isWidgetDocument(name, incoming.type)) { "这不是 JS 组件文件，请选择 .js 文件" }
        return text to "file:$name"
    }

    private fun openHome() {
        startActivity(
            Intent().setComponent(ComponentName(this, "com.yfuse.AppEntry")).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP,
            ),
        )
        finish()
    }
}

internal fun isWidgetDocument(
    name: String?,
    mime: String?,
): Boolean =
    name?.endsWith(".js", ignoreCase = true) == true ||
        mime?.substringBefore(';')?.lowercase() in
        setOf(
            "application/javascript",
            "text/javascript",
            "application/x-javascript",
            "application/ecmascript",
            "text/ecmascript",
        )
