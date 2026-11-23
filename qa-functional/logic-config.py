from pathlib import Path
p=Path('phoneShared/build.gradle.kts');s=p.read_text()
needle='withHostTest { isReturnDefaultValues = true }'
assert s.count(needle)==1
s=s.replace(needle,needle+'''
        withDeviceTestBuilder {
            sourceSetTreeName = "test"
        }.configure {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }''')
needle='        getByName("androidHostTest") {'
assert s.count(needle)==1
s=s.replace(needle,'''        getByName("androidDeviceTest") {
            dependencies {
                implementation(kotlin("test-junit"))
                implementation(libs.androidx.test.runner)
                implementation(libs.androidx.test.junit)
                implementation("androidx.test:rules:1.7.0")
                implementation(libs.androidx.work.runtime)
                implementation(libs.bouncycastle.provider)
            }
        }
'''+needle)
p.write_text(s)

# Android's DEX format for the app's lowest SDK cannot store test method names with spaces.
# Translate only JUnit method identifiers in the disposable checkout, keeping their assertions.
import re,hashlib
for test in Path("composeApp/src/commonTest/kotlin").rglob("*.kt"):
    source = test.read_text()
    def dex_name(match):
        name=match.group(1)
        if " " not in name:return match.group(0)
        new="device_"+re.sub(r"[^a-zA-Z0-9]+","_",name).strip("_")+"_"+hashlib.sha1(name.encode()).hexdigest()[:8]
        if source.count("`"+name+"`")!=1:raise ValueError("JUnit method also referenced by name")
        return "fun "+new
    output=re.sub(r"fun `([^`]+)`(?=\()",dex_name,source)
    if output!=source:test.write_text(output)

# The Android test process has no checkout-relative file path. Embed the signed fixture unchanged.
import gzip,base64,json
fixture=Path("composeApp/src/commonTest/fixtures/calendar-production-r12.json").read_bytes()
encoded=base64.b64encode(gzip.compress(fixture,mtime=0)).decode()
parts=[json.dumps(encoded[i:i+6000]) for i in range(0,len(encoded),6000)]
expression="java.util.zip.GZIPInputStream(android.util.Base64.decode(listOf("+",".join(parts)+").joinToString(\"\"), 0).inputStream()).bufferedReader().use { it.readText() }"
for test in Path("composeApp/src/commonTest/kotlin").rglob("*.kt"):
    source=test.read_text()
    old='File("src/commonTest/fixtures/calendar-production-r12.json").readText()'
    if old in source:test.write_text(source.replace(old,expression))


# These store suites inject host/virtual dispatchers; the normal Android host job executes them.
Path('../logic-host-store-classes.txt').write_text('com.yfuse.feature.calendar.CalendarInteractionTest,com.yfuse.feature.detail.DetailCastTest,com.yfuse.feature.detail.DetailStoreTest,com.yfuse.feature.handoff.DeviceHandoffStoreTest,com.yfuse.feature.home.HomeLibraryShelvesTest,com.yfuse.feature.home.HomeStoreTest,com.yfuse.feature.library.LibraryGridStoreTest,com.yfuse.feature.library.LibraryStoreTest,com.yfuse.feature.personal.PersonalCenterStoreTest,com.yfuse.feature.player.PlayerSeriesLaunchTest,com.yfuse.feature.player.PlayerStoreTest,com.yfuse.feature.search.SearchServerRetryTest,com.yfuse.feature.search.SearchStoreTest,com.yfuse.feature.servers.ServersStoreTest,com.yfuse.feature.trakt.TraktSettingsStoreTest,com.yfuse.feature.watch.WatchTogetherSettingsStoreTest')
