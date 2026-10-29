"""Whole production progress store, conflict resolver and projections; platform/storage fixtures only."""
from pathlib import Path
import subprocess, os, json, hashlib, shutil
ROOT=Path(__file__).resolve().parent
REPO=ROOT.parent.parent
COMMON=REPO/'composeApp/src/commonMain/kotlin/com/yfuse'
TEST=REPO/'composeApp/src/commonTest/kotlin/com/yfuse'
DEPS=Path(os.environ.get('YFUSE_PROGRESS_TEST_DEPS', str(ROOT/'progress-deps')))
cp=os.pathsep.join(map(str, sorted(DEPS.glob('*.jar'))))
names=['core/sync/playback/LibraryResumeState','core/sync/playback/PlaybackStartPosition','core/data/PlaybackProgressProjection']
sources=[COMMON/(n+'.kt') for n in names]+[TEST/(n+'Test.kt') for n in names]
sources += [COMMON/('core/sync/playback/'+n+'.kt') for n in ['PlaybackSyncModels','PlaybackConflictResolver','PlaybackSyncStore']]
sources += list((ROOT/'fixtures-progress').glob('*.kt'))+[ROOT/'fixtures/Logging.kt']
classes=ROOT/'classes-progress'
shutil.rmtree(classes,ignore_errors=True)
plugin=DEPS/'kotlin-serialization-compiler-plugin-embeddable-2.4.21.jar'
cmd=['java','-cp',cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17','-Xplugin='+str(plugin),'-classpath',cp,'-d',str(classes),*map(str,sources)]
r=subprocess.run(cmd,text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
(ROOT/'progress-compile.log').write_text(r.stdout);print(r.stdout)
if r.returncode: raise SystemExit(r.returncode)
r=subprocess.run(['java','-cp',str(classes)+os.pathsep+cp,'org.junit.runner.JUnitCore',*['com.yfuse.'+n.replace('/','.')+'Test' for n in names]],text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=60)
(ROOT/'progress-tests.log').write_text(r.stdout);print(r.stdout)
(ROOT/'progress-result.json').write_text(json.dumps({'exit_code':r.returncode,'scope':__doc__,'sources':[{'path':str(p.relative_to(REPO)),'sha256':hashlib.sha256(p.read_bytes()).hexdigest()} for p in sources]},indent=2))
raise SystemExit(r.returncode)
