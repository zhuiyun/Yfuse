#!/usr/bin/env bash
set -euo pipefail
test "$GITHUB_ACTIONS" = true
root="$GITHUB_WORKSPACE"
work="$RUNNER_TEMP/subtitle-protocol-probe"
mkdir -p "$work"/classes "$work"/dex "$work"/assets/senplayer
python3 - <<'PY'
import zipfile,pathlib,json,hashlib,os
out=pathlib.Path(os.environ['RUNNER_TEMP'])/'subtitle-protocol-probe'
with zipfile.ZipFile('preview/composeApp-release.apk') as z:
    manifest=json.loads(z.read('assets/senplayer/runtime.json'))
    for entry in manifest['files']:
        data=z.read('assets/senplayer/'+entry['asset'])
        assert hashlib.sha256(data).hexdigest()==entry['sha256']
        p=out/'assets/senplayer'/entry['asset'];p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(data)
    (out/'assets/senplayer/runtime.json').write_text(json.dumps(manifest))
PY
cd "$work"
bt=$(find "$ANDROID_HOME/build-tools" -mindepth 1 -maxdepth 1 | sort -V | tail -1)
jar=$(find "$ANDROID_HOME/platforms" -maxdepth 2 -name android.jar | sort -V | tail -1)
cat > AndroidManifest.xml <<'XML'
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.yfuse" android:versionCode="348" android:versionName="1.9.6"><uses-permission android:name="android.permission.INTERNET"/><uses-sdk android:minSdkVersion="28" android:targetSdkVersion="32"/><application android:usesCleartextTraffic="true" android:debuggable="true" android:hardwareAccelerated="true" android:label="Yfuse cloud subtitle diagnostic"><activity android:name="com.yfuse.senplayer.ProbeActivity" android:screenOrientation="landscape" android:exported="true"/></application></manifest>
XML
javac -source 8 -target 8 -classpath "$jar" -d classes "$root/senplayerBridge/src/main/java/com/yfuse/senplayer/SenPlayerSession.java" "$root/qa-sen-subtitle/ProbeActivity.java"
find classes -name '*.class' > classes.list
"$bt/d8" --lib "$jar" --min-api 28 --output dex @classes.list
"$bt/aapt2" link --manifest AndroidManifest.xml -I "$jar" -A assets -o unsigned.apk
(cd dex && zip -q ../unsigned.apk classes.dex)
"$bt/zipalign" -f 4 unsigned.apk aligned.apk
keytool -genkeypair -noprompt -alias qa -dname 'CN=Disposable subtitle diagnostic' -keyalg RSA -keystore harness.jks -storepass android -keypass android -validity 1 >/dev/null 2>&1
mkdir -p "$root/subtitle-probe-input"
"$bt/apksigner" sign --ks harness.jks --ks-pass pass:android --out "$root/subtitle-probe-input/probe.apk" aligned.apk
cd "$root/subtitle-probe-input"
sha256sum probe.apk > SHA256SUMS
