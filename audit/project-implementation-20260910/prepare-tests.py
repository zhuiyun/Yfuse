from pathlib import Path
p=Path('composeApp/src/androidUnitTest/kotlin/com/yfuse/feature/player/MpvSubtitleTextTest.kt');s=p.read_text(encoding='utf-8').replace(', codec =', ', language = null, selected = false, codec =');p.write_text(s,encoding='utf-8')
p=Path('composeApp/src/commonTest/kotlin/com/yfuse/feature/player/DualSubtitlePreferencesTest.kt');s=p.read_text(encoding='utf-8').replace('language = "ZH-Hans")','language = "ZH-Hans", selected = false)').replace('language = "eng")','language = "eng", selected = false)');p.write_text(s,encoding='utf-8')
p=Path('audit/project-implementation-20260910/verify.ps1')
s=p.read_text(encoding='utf-8').replace("':composeApp:compileReleaseKotlinAndroid', ':tvApp:compileDebugKotlinAndroid',", "':composeApp:testReleaseUnitTest', ':tvApp:testDebugUnitTest',")
s=s.replace('build-review.log','feature-tests.log')
Path('audit/project-implementation-20260910/test.ps1').write_text(s,encoding='utf-8')
