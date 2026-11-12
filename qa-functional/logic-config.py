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
