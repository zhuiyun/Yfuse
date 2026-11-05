package com.yfuse.feature.filesource

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.YfChip
import com.yfuse.feature.servers.ServerFormInput

@Composable
internal fun FolderBrowseTools(
    query: String,
    sort: FolderSort,
    descending: Boolean,
    onQuery: (String) -> Unit,
    onSort: (FolderSort) -> Unit,
    onDescending: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ServerFormInput(
            label = "搜索当前文件夹",
            value = query,
            divider = false,
            outlined = true,
            placeholder = "文件或文件夹名称",
            onValueChange = onQuery,
        )
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FolderSort.entries.forEach { option ->
                    YfChip(option.label, option == sort, { onSort(option) }, role = Role.RadioButton)
                }
            }
            YfChip(
                if (descending) "降序" else "升序",
                descending,
                { onDescending(!descending) },
                role = Role.Checkbox,
                onClickLabel = if (descending) "切换为升序" else "切换为降序",
            )
            if (query.isNotEmpty()) YfChip("清除搜索", false, { onQuery("") }, role = Role.Button)
        }
    }
}
