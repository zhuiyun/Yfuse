"""Keep project-owned service transports independent from application/media code.

This is a source architecture check, not a network firewall. Behavioral no-network tests
and both build configurations remain necessary; user media may share the backend host.
"""
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
MODULE = Path('yfuseBackendClient')
APP_SOURCE_ROOTS = (
    Path('composeApp/src/commonMain/kotlin'),
    Path('composeApp/src/androidMain/kotlin'),
    Path('tvApp/src/androidMain/kotlin'),
)
BACKEND_OWNERS = (
    'core/account/',
    'core/handoff/HandoffApi.kt',
    'core/migration/MigrationRelayApi.kt',
    'core/sync/WatchTogetherClient.kt',
    'core/remote/KtorRemoteRelay.kt',
    'core/playback/PlaybackQoeReporter.kt',
    'core/data/OfficialAiringScheduleCatalog.kt',
    'feature/player/PlaybackRemotePolicyRegistry.kt',
    'update/AppUpdateManager.kt',
)
OWNED_API_ROUTE = re.compile(r'/api/v1/(?:account(?:[/"\s]|$)|auth/|migration-relays(?:[/"\s]|$)|calendar/schedules|qoe(?:["\s]|$))')
DEPLOYMENT_ARTIFACT = re.compile(r'/yfuse/(?:update-v2|playback-policy-v1)\.json')
NETWORK_IMPORT = re.compile(
    r'^\s*import\s+(?:io\.ktor\.client\.request\.(?:get|post|put|delete|patch|head|request|prepare\w+)|'
    r'io\.ktor\.client\.plugins\.websocket\.(?:webSocket|webSocketSession))\b', re.MULTILINE,
)
DIRECT_NETWORK = re.compile(r'\bHttpClient\s*\(|\.\s*(?:openConnection|webSocket|webSocketSession)\s*\(')
IMPORT = re.compile(r'^\s*import\s+([\w.*]+)', re.MULTILINE)
DECLARATION = re.compile(
    r'^(?:(?:public|internal|private|data|sealed|enum|expect|actual|open|abstract|inline|suspend|const|annotation|value)\s+)*'
    r'(?:class|interface|object|typealias|fun|val|var)\s+(\w+)', re.MULTILINE,
)
FORBIDDEN_MODULE_REFERENCE = re.compile(
    r'\b(?:AppLog|Settings|embyHttpEngine|migrationRelayHttpEngine)\b|'
    r'\bcom\.yfuse\.(?:app|di|feature|tv)\.',
)


def uncomment(text: str) -> str:
    # Preserve literals: URLs contain // and cannot be removed as line comments.
    return re.sub(r'/\*.*?\*/|(?m:^\s*//[^\n]*)', '', text, flags=re.DOTALL)


def violations(root: Path = ROOT) -> list[str]:
    module = root / MODULE
    source = module / 'src/commonMain/kotlin'
    if not source.is_dir():
        return [f'{MODULE}: independent backend sources are missing']
    files = sorted(module.glob('src/*Main/kotlin/**/*.kt'))
    contents = {path: uncomment(path.read_text(encoding='utf-8')) for path in files}
    declarations = set()
    backend_files_by_package = {}
    for path, text in contents.items():
        package = re.search(r'^package\s+([\w.]+)', text, re.MULTILINE)
        if package:
            declarations.update(f'{package.group(1)}.{name}' for name in DECLARATION.findall(text))
            backend_files_by_package[(package.group(1), path.name)] = path

    errors = []
    for path, text in contents.items():
        label = path.relative_to(root).as_posix()
        if FORBIDDEN_MODULE_REFERENCE.search(text):
            errors.append(f'{label}: backend must not use application state, settings, logs or media engines')
        for imported in IMPORT.findall(text):
            if imported.startswith(('android.', 'androidx.', 'com.russhwolf.settings.', 'org.koin.')):
                errors.append(f'{label}: application/platform dependency {imported}')
            elif imported.startswith('com.yfuse.') and not imported.startswith(
                ('com.yfuse.backend.', 'com.yfuse.watch.protocol.')
            ) and imported not in declarations:
                errors.append(f'{label}: project import outside backend/protocol boundary: {imported}')

    build = module / 'build.gradle.kts'
    if not build.is_file():
        errors.append(f'{MODULE}/build.gradle.kts: independent Gradle module is missing')
    else:
        for dependency in re.findall(r'project\s*\(\s*(?:path\s*=\s*)?["\'](:[^"\']+)["\']\s*\)', build.read_text(encoding='utf-8')):
            if dependency != ':watchTogetherProtocol':
                errors.append(f'{MODULE}/build.gradle.kts: forbidden project dependency {dependency}')

    endpoints = source / 'com/yfuse/backend/BackendEndpoints.kt'
    deployment_hosts = []
    if endpoints.exists():
        deployment_hosts = re.findall(r'https?://([^/"\s]+)', endpoints.read_text(encoding='utf-8'))
    for directory in APP_SOURCE_ROOTS:
        for path in sorted((root / directory).rglob('*.kt')):
            text = uncomment(path.read_text(encoding='utf-8'))
            label = path.relative_to(root).as_posix()
            package = re.search(r'^package\s+([\w.]+)', text, re.MULTILINE)
            counterpart = backend_files_by_package.get((package.group(1), path.name)) if package else None
            if counterpart:
                # Even a class-only file can gain a top-level member later. A matching
                # package/file pair would then emit the same FooKt facade in two JARs.
                errors.append(
                    f'{label}: duplicate package/file with {counterpart.relative_to(root).as_posix()}; '
                    'rename one file to prevent a cross-module JVM facade collision'
                )
            if any(host in text for host in deployment_hosts):
                errors.append(f'{label}: deployment address belongs in BackendEndpoints')
            if OWNED_API_ROUTE.search(text) or DEPLOYMENT_ARTIFACT.search(text):
                errors.append(f'{label}: project-owned service route belongs in yfuseBackendClient')
            suffix = path.as_posix().split('/com/yfuse/', 1)[-1]
            owned = any(suffix == item or item.endswith('/') and suffix.startswith(item) for item in BACKEND_OWNERS)
            if (owned or 'BackendEndpoints' in text) and (DIRECT_NETWORK.search(text) or NETWORK_IMPORT.search(text)):
                errors.append(f'{label}: application adapter must delegate transport to yfuseBackendClient')
    return sorted(set(errors))


def main() -> int:
    errors = violations()
    if errors:
        print('Backend isolation check failed:')
        for error in errors:
            print(f'  {error}')
        return 1
    print('Verified independent backend dependencies and application transport boundaries')
    return 0


if __name__ == '__main__':
    sys.exit(main())
