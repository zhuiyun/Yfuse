package com.yfuse.feature.filesource

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.OverlayButton
import com.yfuse.core.designsystem.OverlayButtonTone
import com.yfuse.core.designsystem.OverlayHeader
import com.yfuse.feature.servers.ServerFormInput

@Composable
internal fun FileSourceMoveDialog(
    name: String,
    parent: List<String>,
    ftp: Boolean,
    onDismiss: () -> Unit,
    onMove: (String) -> Unit,
) {
    var destination by remember(name, parent) { mutableStateOf((parent + name).joinToString("/")) }
    GlassDialog(onDismiss = onDismiss) {
        OverlayHeader(
            title = "重命名或移动",
            subtitle =
                if (ftp) {
                    "目标相对于来源根目录；会先检查同名项。FTP 无法阻止检查后其他设备新建同名文件，请避开同时写入的目录。"
                } else {
                    "目标相对于此来源的根目录；目标文件夹必须存在，同名项不会覆盖。"
                },
            onClose = onDismiss,
        )
        ServerFormInput(label = "目标路径", value = destination, divider = false, onValueChange = { destination = it })
        OverlayButton(
            label = "移动",
            tone = OverlayButtonTone.Primary,
            enabled =
                destination.isNotBlank() && destination != (parent + name).joinToString("/"),
            onClick = { onMove(destination) },
        )
    }
}
