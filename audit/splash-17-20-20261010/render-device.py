"""Render the production Canvas implementation via app_process, without installing an APK."""
from pathlib import Path
import os
import re
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[2]
AUDIT = Path(__file__).resolve().parent
CACHE = Path('C:/Users/app-inkbird/.gradle/caches/modules-2/files-2.1')
SDK = Path('D:/AndroidSDK')
REMOTE = '/data/local/tmp/yfuse-splash1720-20261010'
SERIAL = 'RF8M223V4MD'


def artifact(group, name, version, suffix='jar'):
    return next(p for p in (CACHE / group / name / version).rglob('*.' + suffix) if '-sources' not in p.name and '-javadoc' not in p.name)


def run(args):
    print('Running', args[0], flush=True)
    subprocess.run([str(a) for a in args], cwd=ROOT, check=True)


def block(source, start):
    start = source.index(start)
    opening = source.index('{', start)
    depth = 1
    end = opening + 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[start:end]


APP = ROOT / 'composeApp/src/androidMain/kotlin/com/yfuse/app'
source = (APP / 'SplashChoreography.kt').read_text(encoding='utf-8')
helpers = ['package com.yfuse.app', 'internal val PiF = kotlin.math.PI.toFloat()']
for name in ('span', 'smooth', 'lerp'):
    # These three helpers use expression bodies; retain the original source verbatim.
    helpers.append(re.search(r'internal fun ' + name + r'\([\s\S]*?\): Float = [^\n]+', source)[0])
helpers.append(block(source, 'internal fun scatter('))
craft = (APP / 'WaterFireCrafts.kt').read_text(encoding='utf-8')
helpers.append(re.search(r'internal data class CraftDot\([\s\S]*?\n\)', craft)[0])
(AUDIT / 'RenderSupport.kt').write_text('\n\n'.join(helpers), encoding='utf-8')
theme = (ROOT / 'composeApp/src/commonMain/kotlin/com/yfuse/core/designsystem/Theme.kt').read_text(encoding='utf-8')
(AUDIT / 'RenderEnums.kt').write_text('package com.yfuse.core.designsystem\n\n' + block(theme, 'enum class SplashMark(') + '\n\n' + block(theme, 'enum class SplashAnimation('), encoding='utf-8')
(AUDIT / 'RenderMain.kt').write_text('''package com.yfuse.app
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import com.yfuse.core.designsystem.SplashAnimation
import java.io.File

internal class WaterFireArtwork(root: String) {
    val full = BitmapFactory.decodeFile("$root/water_fire_logo.png")!!
    val layers = listOf("blue", "orange", "gold").map { BitmapFactory.decodeFile("$root/water_fire_$it.png")!! }
    val domino = File(root, "water_fire_domino.csv").readLines().map {
        val a = it.split(',')
        CraftDot(40f + a[0].toFloat() * 4.32f, 40f + a[1].toFloat() * 4.32f, (0xFF000000 or a[2].toLong(16)).toInt())
    }
}

fun main(args: Array<String>) {
    println("Loading artwork")
    val art = WaterFireArtwork(args[0])
    println("Artwork loaded")
    val output = File(args[0], "frames").apply { mkdirs() }
    val variants = listOf(SplashAnimation.Hologram, SplashAnimation.Marble, SplashAnimation.Fan, SplashAnimation.Domino)
    for (variant in variants) {
        println("Creating renderer: $variant")
        val renderer = WaterFireMechanisms(art, variant)
        println("Creating bitmap")
        val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        println("Drawing: $variant")
        for (time in 0..2200 step 50) {
            bitmap.eraseColor(0)
            renderer.draw(canvas, time.toFloat())
            File(output, "${variant.name}-$time.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        println("Rendered ${variant.name}: 45 frames")
        bitmap.recycle()
    }
}
''', encoding='utf-8')

stdlib = artifact('org.jetbrains.kotlin', 'kotlin-stdlib', '2.4.20')
compiler_cp = [
    artifact('org.jetbrains.kotlin', 'kotlin-compiler-embeddable', '2.4.20'), stdlib,
    artifact('org.jetbrains.kotlin', 'kotlin-script-runtime', '2.4.20'),
    artifact('org.jetbrains.kotlin', 'kotlin-daemon-embeddable', '2.4.20'),
    artifact('org.jetbrains.kotlin', 'kotlin-reflect', '2.4.10'),
    artifact('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.9.0'),
    artifact('org.jetbrains', 'annotations', '23.0.0'),
]
dependencies = [artifact('androidx.collection', 'collection-jvm', '1.5.0')]
for group, name in [('androidx.compose.animation', 'animation-core-android'), ('androidx.compose.ui', 'ui-graphics-android'), ('androidx.compose.ui', 'ui-util-android'), ('androidx.compose.ui', 'ui-geometry-android')]:
    with zipfile.ZipFile(artifact(group, name, '1.12.1', 'aar')) as archive:
        path = AUDIT / (name + '.jar')
        path.write_bytes(archive.read('classes.jar'))
        dependencies.append(path)
android = SDK / 'platforms/android-37/android.jar'
if not android.exists():
    android = next((SDK / 'platforms').rglob('android.jar'))
jar = AUDIT / 'renderer.jar'
run(['java', '-Xmx1g', '-cp', os.pathsep.join(map(str, compiler_cp)), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-jvm-target', '17', '-classpath', os.pathsep.join(map(str, [android, stdlib] + dependencies)), '-d', jar, APP / 'WaterFireMechanisms.kt', APP / 'WaterFireMechanismMotion.kt', AUDIT / 'RenderSupport.kt', AUDIT / 'RenderEnums.kt', AUDIT / 'RenderMain.kt'])
dex = AUDIT / 'renderer.zip'
run(['java', '-Xmx2g', '-cp', SDK / 'build-tools/36.1.0/lib/d8.jar', 'com.android.tools.r8.D8', '--min-api', '26', '--lib', android, '--output', dex, jar, stdlib] + dependencies)
adb = [SDK / 'platform-tools/adb.exe', '-s', SERIAL]
run(adb + ['shell', 'mkdir', '-p', REMOTE])
run(adb + ['push', dex, REMOTE + '/renderer.zip'])
res = ROOT / 'composeApp/src/androidMain/res'
for name in ('logo', 'blue', 'orange', 'gold'):
    run(adb + ['push', res / 'drawable-nodpi' / ('water_fire_' + name + '.png'), REMOTE + '/'])
run(adb + ['push', res / 'raw/water_fire_domino.csv', REMOTE + '/'])
run(adb + ['shell', 'env', 'CLASSPATH=' + REMOTE + '/renderer.zip', 'app_process', '-Xmx128m', '/system/bin', '--nice-name=yfuse-splash-renderer', 'com.yfuse.app.RenderMainKt', REMOTE])
run(adb + ['pull', REMOTE + '/frames', AUDIT / 'device-frames'])
print('Done: production Canvas frames are in', AUDIT / 'device-frames')
