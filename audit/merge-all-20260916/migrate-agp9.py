from pathlib import Path
import re

root = Path(__file__).resolve().parents[2]
def write(path, text):
    p = root / path
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(text, encoding='utf-8', newline='\n')
def block(text, marker):
    start = text.index(marker)
    opening = text.index('{', start)
    level = 1
    end = opening + 1
    while level:
        level += (text[end] == '{') - (text[end] == '}')
        end += 1
    return text[opening+1:end-1], start, end
def body(text, marker): return block(text, marker)[0]

phone = (root/'audit/merge-all-20260916/pre-agp9/composeApp.gradle.kts').read_text(encoding='utf-8')
tv = (root/'audit/merge-all-20260916/pre-agp9/tvApp.gradle.kts').read_text(encoding='utf-8')
phone_common = body(phone, 'commonMain.dependencies {')
phone_android = body(phone, 'androidMain.dependencies {')
phone_tests = body(phone, 'commonTest.dependencies {') + body(phone, 'androidUnitTest.dependencies {')
tv_common = body(body(tv, 'commonMain {'), 'dependencies {')
tv_android = body(body(tv, 'androidMain {'), 'dependencies {')
tv_tests = body(body(tv, 'androidUnitTest {'), 'dependencies {')

for module, original, common, android_deps in [('composeApp',phone,phone_common,phone_android), ('tvApp',tv,tv_common,tv_android)]:
    _, start, end = block(original, 'kotlin {\n    androidTarget')
    result = original[:start] + original[end:]
    result = result.replace('    alias(libs.plugins.multiplatform)\n','')
    result = result.replace('compileSdk = 36','compileSdk { version = release(37) { minorApiLevel = 0 } }')
    if module == 'composeApp':
        source_config = '''
        getByName("main") {
            manifest.srcFile("src/androidMain/AndroidManifest.xml")
        }
        getByName("androidTest") {
            kotlin.directories += "src/androidInstrumentedTest/kotlin"
        }
'''
        result = result.replace('    sourceSets {\n', '    sourceSets {\n' + source_config, 1)
        result = result.replace('manifest.srcFile("src/performance/AndroidManifest.xml")', 'manifest.srcFile("src/performance/AndroidManifest.xml")\n                kotlin.directories += "src/performance/kotlin"')
        result = result.replace('// API 36 is the release baseline. Predictive back remains explicitly opted out in the\n    // manifest by product decision while the rest of the Android 16 behavior is supported.', '// Compile against API 37 for current dependencies; targetSdk 36 remains the runtime baseline.')
    else:
        result = result.replace('getByName("main") {', 'getByName("main") {\n            manifest.srcFile("src/androidMain/AndroidManifest.xml")',1)
        result = result.replace('                rootProject.file("composeApp/src/androidMain/res"),\n','')
        start = result.index('/*\n * P0 sharing boundary')
        end = result.index('*/',start)+2
        result = result[:start]+'// The TV shell owns signing, manifest, and runtime dependencies; :tvShared owns Kotlin sources.'+result[end:]
    result += '\nkotlin { jvmToolchain(17) }\n\ndependencies {\n    implementation(project(":'+('phoneShared' if module=='composeApp' else 'tvShared')+'"))\n' + android_deps + '\n}\n'
    write(module+'/build.gradle.kts',result)

# The two shared libraries retain the existing phone/TV source and dependency boundaries.
# Local native AARs are compile-only here; application modules own runtime packaging.
for module, common, tests, is_tv in [('phoneShared',phone_common,phone_tests,False),('tvShared',tv_common,tv_tests,True)]:
    common = common.replace('implementation(', 'api(')
    android_deps = '\n'.join(line for line in (tv_android if is_tv else phone_android).splitlines() if re.match(r'\s*(?:implementation|compileOnly)\(libs\.',line))
    # Shared code always sees the complete API; per-app runtime choices remain in the shell.
    android_deps = re.sub(r'(?:implementation|compileOnly)\(', 'compileOnly(', android_deps)
    android_deps = '\n'.join(dict.fromkeys(android_deps.splitlines()))
    tests = tests.replace('if (nativeOnlyRuntime)', 'if (true)')
    android_roots = '"../composeApp/src/androidMain/kotlin"' + (', "../tvApp/src/androidMain/kotlin"' if is_tv else '')
    test_roots = '"../composeApp/src/androidUnitTest/kotlin/com/yfuse/tv", "../tvApp/src/androidUnitTest/kotlin", "../tvApp/src/test/kotlin"' if is_tv else '"../composeApp/src/androidUnitTest/kotlin"'
    test_common = '' if is_tv else 'commonTest.kotlin.srcDir("../composeApp/src/commonTest/kotlin")'
    write(module+'/build.gradle.kts', '''import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.serialization)
}

kotlin {
    android {
        namespace = "com.yfuse.shared"
        compileSdk { version = release(37) { minorApiLevel = 0 } }
        minSdk = 26
        androidResources { enable = true }
        withHostTest { isReturnDefaultValues = true }
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
        localDependencySelection { selectBuildTypeFrom.set(listOf("debug", "release")) }
    }
    sourceSets {
        all { languageSettings.optIn("kotlinx.coroutines.ExperimentalCoroutinesApi") }
        commonMain {
            kotlin.srcDir("../composeApp/src/commonMain/kotlin")
            dependencies {'''+common+'''
            }
        }
        androidMain {
            kotlin.srcDirs('''+android_roots+''')
            dependencies {
                compileOnly(project(":mdkAndroid"))
                compileOnly(files("../composeApp/libs/libmpv-release.aar"))
'''+android_deps+'''
            }
        }
        '''+test_common+'''
        getByName("androidHostTest") {
            kotlin.srcDirs('''+test_roots+''')
            dependencies {'''+tests+'''
            }
        }
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.res?.addStaticSourceDirectory("../composeApp/src/androidMain/res")
    }
}
''')

catalog = (root/'gradle/libs.versions.toml').read_text()
for key,value in [('agp','9.1.1'),('compose','1.12.0'),('coil','3.6.2'),('okhttp','5.5.0')]:
    catalog = re.sub(r'^'+key+r' = "[^"]+"',key+' = "'+value+'"',catalog,flags=re.M)
catalog = re.sub(r'^kotlin-android = .*\n','',catalog,flags=re.M)
catalog = re.sub(r'^android-kmp-library = .*\n', '', catalog, flags=re.M)
catalog += 'android-kmp-library = { id = "com.android.kotlin.multiplatform.library", version.ref = "agp" }\n'
write('gradle/libs.versions.toml',catalog)
base = (root/'build.gradle.kts').read_text().replace('    alias(libs.plugins.kotlin.android) apply false','    alias(libs.plugins.android.kmp.library) apply false')
base = base.replace('"config/ktlint/$name-baseline.xml"', '"config/ktlint/${when (name) { "phoneShared" -> "composeApp"; "tvShared" -> "tvApp"; else -> name }}-baseline.xml"')
base = base.replace('if (project.name == "tvApp")', 'if (project.name == "tvShared")')
write('build.gradle.kts',base)
settings = (root/'settings.gradle.kts').read_text()
start = settings.index('// Android TV is an independent application module')
end = settings.index('include(":tvApp")',start)
settings = settings[:start]+'// App shells own packaging; KMP libraries own the existing shared source trees.\ninclude(":phoneShared")\ninclude(":tvShared")\n'+settings[end:]
write('settings.gradle.kts',settings)
for module in ['macrobenchmark','mdkAndroid']:
    s=(root/module/'build.gradle.kts').read_text().replace('    alias(libs.plugins.kotlin.android)\n','').replace('compileSdk = 36','compileSdk { version = release(37) { minorApiLevel = 0 } }')
    if module=='mdkAndroid': s=s.replace('android {','android {\n    enableKotlin = false',1)
    write(module+'/build.gradle.kts',s)

fields = {'DEBUG':'Boolean','APPLICATION_ID':'String','VERSION_NAME':'String','VERSION_CODE':'Int','BUILD_REVISION':'String','TMDB_TOKEN':'String','UPDATE_MANIFEST_PUBLIC_KEY':'String','YFUSE_MDK_INCLUDED':'Boolean','YFUSE_NATIVE_ONLY_RUNTIME':'Boolean','YFUSE_YCORE_GPU_INCLUDED':'Boolean','YFUSE_CAST_RECEIVER_APPLICATION_ID':'String','YFUSE_PACKAGE_PROFILE':'String'}
write('composeApp/src/androidMain/kotlin/com/yfuse/core/platform/AppBuildConfig.kt', '''package com.yfuse.core.platform

/** Variant values are supplied by the application before providers and startup code run. */
data class AppBuildValues(
'''+''.join('    val '+k+': '+v+',\n' for k,v in fields.items())+''')

object AppBuildConfig {
    @Volatile
    private var installed: AppBuildValues? = null

    fun install(values: AppBuildValues) {
        check(installed == null || installed == values) { "Application build configuration cannot change in a process" }
        installed = values
    }

    private val values: AppBuildValues
        get() = checkNotNull(installed) { "Application must install build configuration in attachBaseContext" }

'''+''.join('    val '+k+': '+v+' get() = values.'+k+'\n' for k,v in fields.items())+'}\n')
for directory in ['composeApp/src/androidMain','tvApp/src/androidMain']:
    for p in (root/directory).rglob('*.kt'):
        s=p.read_text(encoding='utf-8')
        s=s.replace('import com.yfuse.R','import com.yfuse.shared.R')
        if 'BuildConfig' in s:
            s=s.replace('import com.yfuse.BuildConfig','import com.yfuse.core.platform.AppBuildConfig as BuildConfig')
            s=s.replace('com.yfuse.BuildConfig.', 'com.yfuse.core.platform.AppBuildConfig.')
            if re.search(r'(?<![\w.])BuildConfig\.',s) and 'AppBuildConfig as BuildConfig' not in s:
                s=s.replace('\n\n','\n\nimport com.yfuse.core.platform.AppBuildConfig as BuildConfig\n',1)
        # Sources in package com.yfuse previously resolved the app R class without an import.
        if s.startswith('package com.yfuse\n') and re.search(r'(?<![\w.])R\.',s) and 'import com.yfuse.shared.R' not in s:
            s=s.replace('\n\n','\n\nimport com.yfuse.shared.R\n',1)
        if p.name=='YfuseApp.kt': s=s.replace('class YfuseApp :','open class YfuseApp :')
        if p.name=='TvApplication.kt': s=s.replace('class TvApplication :','open class TvApplication :')
        if s != p.read_text(encoding='utf-8'): write(str(p.relative_to(root)),s)

for module,parent,name in [('composeApp','com.yfuse.YfuseApp','PhoneApplication'),('tvApp','com.yfuse.tv.TvApplication','TvApplicationShell')]:
    write(module+'/src/main/kotlin/com/yfuse/shell/'+name+'.kt', '''package com.yfuse.shell

import android.content.Context
import com.yfuse.BuildConfig
import com.yfuse.core.platform.AppBuildConfig
import com.yfuse.core.platform.AppBuildValues

class '''+name+' : '+parent+'''() {
    override fun attachBaseContext(base: Context) {
        AppBuildConfig.install(
            AppBuildValues(
'''+''.join('                '+k+' = BuildConfig.'+k+',\n' for k in fields)+'''            ),
        )
        super.attachBaseContext(base)
    }
}
''')
    p=module+'/src/androidMain/AndroidManifest.xml'
    write(p,(root/p).read_text().replace('android:name="'+parent+'"','android:name="com.yfuse.shell.'+name+'"'))

app=(root/'composeApp/src/commonMain/kotlin/com/yfuse/app/App.kt').read_text()
app=app.replace('import com.yfuse.core.designsystem.AppIcons','import com.yfuse.core.designsystem.AppTypography\nimport com.yfuse.core.designsystem.ThemeText as Text\nimport com.yfuse.core.designsystem.AppIcons')
write('composeApp/src/commonMain/kotlin/com/yfuse/app/App.kt',app)

