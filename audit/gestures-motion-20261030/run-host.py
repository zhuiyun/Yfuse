"""Whole production JVM logic, with explicit storage/Compose-state fixtures; not Android UI validation."""
import os, pathlib, subprocess, hashlib, json
root=pathlib.Path(__file__).resolve().parent
repo=root.parent.parent
src=repo/'composeApp/src'
deps=pathlib.Path(os.environ['YFUSE_TEST_DEPS'])
cp=os.pathsep.join(str(p) for p in sorted(deps.glob('*.jar')))
common=['feature/player/'+n for n in ['PictureHoldSeek','PlayerSeekGestures','PlayerGestureCommands','PlayerInteractionPolicy']]
common+=['core/data/CalendarLookupBudget']
common+=['core/sync/playback/'+n for n in ['PlaybackSyncModels','PlaybackConflictResolver','PlaybackSyncStore','PlaybackSessionEpochs','PlaybackIdentityBindings']]
tests=['feature/player/'+n for n in ['PictureHoldSeekTest','PlayerSeekGesturesTest','PlayerGestureCommandsTest']]
tests+=['core/data/CalendarLookupBudgetTest','core/sync/playback/PlaybackSessionRetryTest']
sources=[src/'commonMain/kotlin/com/yfuse'/(p+'.kt') for p in common]
sources+=[src/'commonTest/kotlin/com/yfuse'/(p+'.kt') for p in tests]
sources+=[src/'androidMain/kotlin/com/yfuse/core/logging/VerifiedDiagnosticExport.kt',src/'androidUnitTest/kotlin/com/yfuse/core/logging/VerifiedDiagnosticExportTest.kt']
sources+=list((root/'fixtures').glob('*.kt'))
classes=root/'classes'
cmd=['java','-cp',cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17','-classpath',cp,'-Xplugin='+str(deps/'kotlin-serialization-compiler-plugin-embeddable-2.4.21.jar'),'-d',str(classes),*map(str,sources)]
r=subprocess.run(cmd,text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
(root/'compile.log').write_text(r.stdout);print(r.stdout)
if r.returncode:raise SystemExit(r.returncode)
names=['com.yfuse.'+p.replace('/','.') for p in tests]+['com.yfuse.core.logging.VerifiedDiagnosticExportTest']
r=subprocess.run(['java','-cp',str(classes)+os.pathsep+cp,'org.junit.runner.JUnitCore',*names],text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=120)
(root/'tests.log').write_text(r.stdout);print(r.stdout)
(root/'result.json').write_text(json.dumps({'scope':__doc__,'exit_code':r.returncode,'sources':[{'path':str(p.relative_to(repo)),'sha256':hashlib.sha256(p.read_bytes()).hexdigest()} for p in sources]},indent=2)+'\n')
raise SystemExit(r.returncode)
