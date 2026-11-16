set -euo pipefail
tmpdir=$(mktemp -d)
cp qa-functional/input-driver.java "$tmpdir/Driver.java"
cd "$tmpdir"
bt=$(find "$ANDROID_HOME/build-tools" -mindepth 1 -maxdepth 1 | sort -V | tail -1)
jar=$(find "$ANDROID_HOME/platforms" -maxdepth 2 -name android.jar | sort -V | tail -1)
mkdir -p classes dex
cat > AndroidManifest.xml <<'XML'
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="qa.yfuse.gesture"><uses-sdk android:minSdkVersion="26" android:targetSdkVersion="35"/><application android:label="Input QA" android:hasCode="true"/><instrumentation android:name="qa.yfuse.gesture.Driver" android:targetPackage="qa.yfuse.gesture"/></manifest>
XML
javac -source 8 -target 8 -classpath "$jar" -d classes Driver.java
"$bt/d8" --lib "$jar" --min-api 26 --output dex classes/qa/yfuse/gesture/Driver.class
"$bt/aapt2" link --manifest AndroidManifest.xml -I "$jar" -o unsigned.apk
(cd dex && zip -q ../unsigned.apk classes.dex)
"$bt/zipalign" -f 4 unsigned.apk aligned.apk
keytool -genkeypair -noprompt -alias qa -dname 'CN=Disposable QA' -keyalg RSA -keystore harness.jks -storepass android -keypass android -validity 1 >/dev/null 2>&1
"$bt/apksigner" sign --ks harness.jks --ks-pass pass:android --out driver.apk aligned.apk
adb install -r driver.apk
