package com.yfuse.feature.servers

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.arkivanov.mvikotlin.extensions.coroutines.states
import com.yfuse.core.model.SavedServer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServersScreen(component: ServersComponent) {
    val state by component.store.states.collectAsState(component.store.state)
    val store = component.store

    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("服务器") },
                navigationIcon = {
                    IconButton(onClick = component.onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { store.accept(ServersIntent.OpenAddDialog) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Icon(Icons.Rounded.Add, contentDescription = "添加服务器")
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (state.servers.isEmpty()) {
                EmptyServers(Modifier.align(Alignment.Center)) {
                    store.accept(ServersIntent.OpenAddDialog)
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 168.dp),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(state.servers, key = { it.id }) { server ->
                        ServerCard(
                            server = server,
                            isDefault = server.id == state.defaultServerId,
                            onClick = { store.accept(ServersIntent.SelectDefault(server.id)) },
                            onSetDefault = { store.accept(ServersIntent.SelectDefault(server.id)) },
                            onRemove = { store.accept(ServersIntent.Remove(server.id)) },
                        )
                    }
                }
            }
        }
    }

    if (state.dialogVisible) {
        AddServerDialog(
            form = state.form,
            onUrl = { store.accept(ServersIntent.UrlChanged(it)) },
            onUsername = { store.accept(ServersIntent.UsernameChanged(it)) },
            onPassword = { store.accept(ServersIntent.PasswordChanged(it)) },
            onSubmit = { store.accept(ServersIntent.Submit) },
            onDismiss = { store.accept(ServersIntent.DismissDialog) },
        )
    }
}

@Composable
private fun EmptyServers(modifier: Modifier, onAdd: () -> Unit) {
    Column(modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            Icons.Rounded.Dns,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text("还没有添加服务器", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onAdd) { Text("添加 Emby 服务器") }
    }
}

@Composable
private fun ServerCard(
    server: SavedServer,
    isDefault: Boolean,
    onClick: () -> Unit,
    onSetDefault: () -> Unit,
    onRemove: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    ElevatedCard(
        shape = RoundedCornerShape(18.dp),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (isDefault) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.size(44.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (isDefault) Icons.Rounded.CheckCircle else Icons.Rounded.Dns,
                            contentDescription = null,
                            tint = if (isDefault) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                Box {
                    IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = "更多", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (!isDefault) {
                            DropdownMenuItem(text = { Text("设为默认") }, onClick = { menuOpen = false; onSetDefault() })
                        }
                        DropdownMenuItem(text = { Text("移除") }, onClick = { menuOpen = false; onRemove() })
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    server.serverName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (isDefault) {
                    Spacer(Modifier.size(6.dp))
                    DefaultBadge()
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                server.userName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                server.baseUrl.substringAfter("://"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DefaultBadge() {
    Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.primaryContainer) {
        Text(
            "默认",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun AddServerDialog(
    form: LoginForm,
    onUrl: (String) -> Unit,
    onUsername: (String) -> Unit,
    onPassword: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加服务器") },
        text = {
            Column {
                OutlinedTextField(
                    value = form.url,
                    onValueChange = onUrl,
                    label = { Text("服务器地址") },
                    placeholder = { Text("http://192.168.1.10:8096") },
                    singleLine = true,
                    enabled = !form.submitting,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = form.username,
                    onValueChange = onUsername,
                    label = { Text("用户名") },
                    singleLine = true,
                    enabled = !form.submitting,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = form.password,
                    onValueChange = onPassword,
                    label = { Text("密码") },
                    singleLine = true,
                    enabled = !form.submitting,
                    isError = form.error != null,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (form.error != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(form.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSubmit, enabled = form.canSubmit) {
                if (form.submitting) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("登录")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !form.submitting) { Text("取消") }
        },
    )
}
