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
