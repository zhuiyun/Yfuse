from pathlib import Path
root = Path('D:/Demo/Yfuse')
def edit(path, old, new, count=-1):
    p=root/path
    s=p.read_text(encoding='utf-8')
    if old not in s: raise RuntimeError(f'Pattern not found: {path}: {old[:60]}')
    p.write_text(s.replace(old,new,count),encoding='utf-8',newline='\n')
for path in ['composeApp/src/commonMain/kotlin/com/yfuse/feature/profile/AddServerDialog.kt','tvApp/src/androidMain/kotlin/com/yfuse/tv/ui/TvServersSettingsScreens.kt']:
    p=root/path
    s=p.read_text(encoding='utf-8')
    s=s.replace('import com.yfuse.feature.servers.ServersState','import com.yfuse.feature.servers.rememberServerConnectionIntent\nimport com.yfuse.feature.servers.ServersState')
    start=s.index('fun AddServerDialog(') if 'AddServerDialog' in path else s.index('private fun TvServerDialog(')
    prefix=s[:start]; tail=s[start:]
    tail=tail.replace('onIntent(', 'sendIntent(')
    needle='    val palette = LocalPalette.current' if 'AddServerDialog' in path else '    val hostRequester = remember { FocusRequester() }'
    tail=tail.replace(needle,'    val sendIntent = rememberServerConnectionIntent(state, onIntent)\n'+needle,1)
    p.write_text(prefix+tail,encoding='utf-8',newline='\n')
p='composeApp/src/commonMain/kotlin/com/yfuse/feature/servers/ServersScreen.kt'
edit(p,'    val store = component.store','    val store = component.store\n    val sendIntent = rememberServerConnectionIntent(state, store::accept)',1)
edit(p,'store.accept(', 'sendIntent(')
edit(p,'onIntent = store::accept','onIntent = sendIntent')
for p, token in [('composeApp/src/androidMain/kotlin/com/yfuse/core/network/LocalNetworkPermissionUi.android.kt','launcher'),('composeApp/src/androidMain/kotlin/com/yfuse/feature/profile/PermissionHealthTools.android.kt','requestPermission'),('tvApp/src/androidMain/kotlin/com/yfuse/tv/ui/TvPermissionHealthScreen.kt','requestLocalNetwork')]:
    edit(p,f'?.let({token}::launch)',f'?.let {{ {token}.launch(it) }}')
for p, rootname, marker in [('composeApp/src/commonMain/kotlin/com/yfuse/app/App.kt','root','        BindProductServices(root)'),('tvApp/src/androidMain/kotlin/com/yfuse/tv/ui/TvApp.kt','component','        com.yfuse.app.BindProductServices(component)')]:
    edit(p,'import androidx.compose.runtime.Composable','import androidx.compose.runtime.rememberCoroutineScope\nimport com.yfuse.core.network.LocalNetworkAccessNotice\nimport kotlinx.coroutines.launch\nimport androidx.compose.runtime.Composable',1)
    code=f'''{marker}
        val savedServers by {rootname}.dependencies.serverRegistry.data.collectAsState()
        val permissionScope = rememberCoroutineScope()
        LocalNetworkAccessNotice(hasServers = savedServers.servers.isNotEmpty()) {{
            permissionScope.launch {{ {rootname}.dependencies.serverHealthMonitor.refreshAll() }}
        }}'''
    edit(p,marker,code,1)