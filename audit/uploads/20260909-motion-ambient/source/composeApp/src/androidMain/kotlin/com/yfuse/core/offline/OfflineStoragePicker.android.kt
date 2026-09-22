package com.yfuse.core.offline

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun rememberOfflineStorageDirectoryPicker(onSelected: (treeUri: String, label: String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                val flags =
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                runCatching {
                    context.contentResolver.takePersistableUriPermission(uri, flags)
                }.onSuccess {
                    onSelected(uri.toString(), storageTreeLabel(uri))
                }
            }
        }
    return remember(launcher) { { launcher.launch(null) } }
}

private fun storageTreeLabel(uri: Uri): String =
    runCatching { DocumentsContract.getTreeDocumentId(uri) }
        .getOrNull()
        ?.let(Uri::decode)
        ?.substringAfter(':')
        ?.trim('/')
        ?.takeIf(String::isNotEmpty)
        ?.let { "自选目录 · $it" }
        ?: "SD 卡或自选目录"
