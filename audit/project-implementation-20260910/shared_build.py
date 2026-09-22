from pathlib import Path
p=Path('composeApp/build.gradle.kts')
s=p.read_text(encoding='utf-8')
a=s.index('// A package version can be rebuilt from different commits.')
b=s.index('// TMDB token comes from local.properties',a)
block=s[a:b]
Path('gradle/diagnostic-build.gradle.kts').write_text(block+'extra["yfuseDiagnosticBuildRevision"] = diagnosticBuildRevision\n',encoding='utf-8')
s=s[:a]+'''apply(from = rootProject.file("gradle/diagnostic-build.gradle.kts"))
val diagnosticBuildRevision = extra["yfuseDiagnosticBuildRevision"] as String

'''+s[b:]
p.write_text(s,encoding='utf-8')
p=Path('tvApp/build.gradle.kts')
s=p.read_text(encoding='utf-8')
a=s.index('val versionProperties =')
s=s[:a]+'''apply(from = rootProject.file("gradle/diagnostic-build.gradle.kts"))
val diagnosticBuildRevision = extra["yfuseDiagnosticBuildRevision"] as String

'''+s[a:]
s=s.replace('        buildConfigField("String", "TMDB_TOKEN",', '        buildConfigField("String", "BUILD_REVISION", "\\\"$diagnosticBuildRevision\\\"")\n        buildConfigField("String", "TMDB_TOKEN",')
p.write_text(s,encoding='utf-8')
