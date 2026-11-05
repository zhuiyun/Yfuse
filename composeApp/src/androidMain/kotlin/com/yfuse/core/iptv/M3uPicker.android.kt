package com.yfuse.core.iptv

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
actual fun rememberM3uPicker(onResult: (Result<String>) -> Unit): () -> Unit {
    val context = LocalContext.current
    val callback by rememberUpdatedState(onResult)
    val scope = rememberCoroutineScope()
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                scope.launch {
                    val result =
                        try {
                            Result.success(
                                withContext(Dispatchers.IO) {
                                    requireNotNull(context.contentResolver.openInputStream(uri)).use { input ->
                                        val output = java.io.ByteArrayOutputStream()
                                        val buffer = ByteArray(8192)
                                        while (true) {
                                            val count = input.read(buffer)
                                            if (count < 0) break
                                            require(output.size() + count <= MAX_M3U_BYTES) { "订阅超过 4 MB" }
                                            output.write(buffer, 0, count)
                                        }
                                        output.toString("UTF-8")
                                    }
                                },
                            )
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            Result.failure(error)
                        }
                    callback(result)
                }
            }
        }
    return remember(launcher) {
        {
            launcher.launch(
                arrayOf(
                    "audio/x-mpegurl",
                    "application/vnd.apple.mpegurl",
                    "audio/mpegurl",
                    "text/plain",
                    "application/octet-stream",
                ),
            )
        }
    }
}
