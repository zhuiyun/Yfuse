package com.yfuse.feature.profile

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.yfuse.core.logging.AppLog
import com.yfuse.core.util.androidAppContext

@Composable
actual fun rememberBackgroundImagePicker(onPicked: (String?) -> Unit): () -> Unit {
    val context = LocalContext.current
    // OpenDocument rather than PickVisualMedia: only the Storage Access Framework issues a
    // grant that survives a reboot, and a wallpaper that stops loading on the next launch is
    // worse than no wallpaper at all.
    val launcher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri: Uri? ->
            if (uri == null) {
                onPicked(null)
                return@rememberLauncherForActivityResult
            }
            val persisted =
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }.isSuccess
            if (!persisted) {
                // Some providers hand back a one-shot URI. It still displays now, and the
                // preference is cleared on the next launch when the read fails, so accepting it
                // is better than refusing a picture the user just chose.
                AppLog.warning(
                    category = "appearance.background",
                    event = "persist_permission_unavailable",
                    message = "Background image URI could not be persisted; it may not survive a restart",
                )
            }
            onPicked(uri.toString())
        }
    return remember(launcher) { { launcher.launch(arrayOf("image/*")) } }
}

actual fun releaseBackgroundImage(uri: String) {
    val resolver = androidAppContext?.contentResolver ?: return
    runCatching {
        resolver.releasePersistableUriPermission(
            Uri.parse(uri),
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
    }.onFailure { error ->
        AppLog.warning(
            category = "appearance.background",
            event = "release_permission_failed",
            message = "A background image grant could not be released",
            throwable = error,
        )
    }
}
