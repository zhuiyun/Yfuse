from pathlib import Path
import os
import subprocess
import sys
import zipfile

root = Path(__file__).resolve().parent
repo = root.parents[3]
gradle_home = Path(os.environ.get('GRADLE_USER_HOME', Path.home() / '.gradle'))
cache = gradle_home / 'caches' / 'modules-2' / 'files-2.1'
java_home = os.environ.get('JAVA_HOME')
java = str(Path(java_home) / 'bin' / ('java.exe' if os.name == 'nt' else 'java')) if java_home else 'java'


def jar(group, module, version):
    matches = [p for p in (cache/group/module/version).glob('*/*.jar')
               if not p.name.endswith(('-sources.jar', '-javadoc.jar'))]
    if not matches:
        raise ValueError(f'Missing {module} {version}')
    return str(matches[0])


common = [jar('org.jetbrains.kotlin', 'kotlin-stdlib', '2.4.21'),
          jar('org.jetbrains', 'annotations', '13.0')]
compiler = common + [jar('org.jetbrains.kotlin', name, '2.4.21') for name in
                     ['kotlin-compiler-embeddable', 'kotlin-script-runtime', 'kotlin-build-tools-api']]
compiler += [jar('org.jetbrains.kotlin', 'kotlin-reflect', '1.6.10'),
             jar('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.8.0')]
aar = next((cache/'com.squareup.okhttp3/okhttp-android/5.5.1').glob('*/*.aar'))
with zipfile.ZipFile(aar) as z:
    (root/'okhttp.jar').write_bytes(z.read('classes.jar'))
runtime = common + [str(root/'okhttp-jvm.jar'),
                    jar('com.squareup.okio', 'okio-jvm', '3.18.2'),
                    jar('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.11.0'),
                    jar('org.jetbrains.kotlin', 'kotlin-test', '2.4.21'),
                    jar('org.jetbrains.kotlin', 'kotlin-test-junit', '2.4.21'),
                    jar('junit', 'junit', '4.13.2'),
                    jar('org.hamcrest', 'hamcrest-core', '1.3'),
                    jar('com.squareup.okhttp3', 'mockwebserver', '5.5.1'),
                    jar('com.squareup.okhttp3', 'mockwebserver3', '5.5.1')]
# Unrelated app pool initialization is not needed: the test supplies its own client.
(root/'Pool.kt').write_text('package com.yfuse.core.network\ninternal val sharedOriginConnectionPool = okhttp3.ConnectionPool()\n', encoding='utf-8')
adaptive = (repo/'composeApp/src/androidMain/kotlin/com/yfuse/core2/android/AndroidAdaptiveHttpMediaTransport.kt').read_text(encoding='utf-8')
exception = adaptive[adaptive.index('internal open class AndroidRangeResponseException('):adaptive.index('private fun Int.toAdaptiveRangeFailureKind')]
(root/'RangeException.kt').write_text('package com.yfuse.core2.android\nimport java.io.IOException\nimport com.yfuse.core2.network.YTransportFailureKind\n'+exception, encoding='utf-8')
mode = sys.argv[1]
transport = root/'AndroidHttpMediaTransport-before.kt' if mode == 'before' else repo/'composeApp/src/androidMain/kotlin/com/yfuse/core2/android/AndroidHttpMediaTransport.kt'
sources = [transport, root/'Pool.kt', root/'RangeException.kt', root/'ExistingCancellationTest.kt'] + [repo/rel for rel in [
    'composeApp/src/commonMain/kotlin/com/yfuse/core2/network/YMediaTransport.kt',
    'composeApp/src/commonMain/kotlin/com/yfuse/core2/network/YTransportRetryPolicy.kt',
    'composeApp/src/androidMain/kotlin/com/yfuse/core2/android/AndroidHttpRangeHeaders.kt',
    'composeApp/src/androidMain/kotlin/com/yfuse/core2/android/MediaCredentialScope.kt',
    'composeApp/src/androidUnitTest/kotlin/com/yfuse/core2/android/AndroidHttpMediaTransportReadOwnershipTest.kt']]
classes = root/('classes-'+mode)
classes.mkdir(exist_ok=True)
args = [java, '-cp', os.pathsep.join(compiler), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
        '-no-stdlib', '-no-reflect', '-jvm-target', '17', '-classpath', os.pathsep.join(runtime),
        '-d', str(classes)] + [str(p) for p in sources]
compile_result = subprocess.run(args, capture_output=True, text=True, timeout=100)
(root/(mode+'-compile.txt')).write_text(compile_result.stdout+compile_result.stderr, encoding='utf-8')
if compile_result.returncode:
    print(compile_result.stderr[-6000:])
    raise SystemExit(compile_result.returncode)
result = subprocess.run([java, '-cp', os.pathsep.join([str(classes)]+runtime), 'org.junit.runner.JUnitCore',
                         'com.yfuse.core2.android.AndroidHttpMediaTransportReadOwnershipTest',
                         'com.yfuse.core2.android.ExistingCancellationTest'],
                        capture_output=True, text=True, timeout=30)
(root/(mode+'-result.txt')).write_text(result.stdout+result.stderr, encoding='utf-8')
print(result.stdout+result.stderr)
raise SystemExit(result.returncode)
