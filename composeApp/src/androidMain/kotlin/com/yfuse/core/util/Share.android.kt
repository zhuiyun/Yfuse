package com.yfuse.core.util

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun rememberShareHandler(): ShareHandler {
    val context = LocalContext.current
    return remember(context) { AndroidShareHandler(context) }
}

private class AndroidShareHandler(
    private val context: Context,
) : ShareHandler {
    override fun shareText(text: String) {
        val send =
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }
        // A chooser rather than a direct start: an invite has no single natural target, and
        // FLAG_ACTIVITY_NEW_TASK is required because this may be invoked from a Compose
        // context whose activity isn't guaranteed to be the task root.
        context.startActivity(
            Intent.createChooser(send, "邀请一起看").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    override fun copyText(text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("Yfuse 文本", text))
        // Android 13+ shows its own copy confirmation, so only older versions need this.
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
        }
    }

    override fun copySensitiveText(text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = ClipData.newPlainText("Yfuse 安全凭证", text)
        clip.description.extras =
            PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        clipboard?.setPrimaryClip(clip)
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(context, "已复制安全凭证", Toast.LENGTH_SHORT).show()
        }
    }

    override fun copyRoomCode(roomCode: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("Yfuse 房间码", roomCode))
        // Android 13+ shows the system clipboard preview; older versions need app feedback.
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(context, "房间码已复制", Toast.LENGTH_SHORT).show()
        }
    }
}
