from collections import Counter
from pathlib import Path
import hashlib,json,sys
symbols=('com.yfuse.feature.player.Core2SurfaceKt','com.yfuse.feature.player.SenPlayerSubtitles','com.yfuse.feature.player.BottomSubtitleStackKt','ExoSubtitleBlock')
counts=Counter()
digest=hashlib.sha256()
mapping=Path(sys.argv[1])
with mapping.open('rb') as f:
 header=f.readline()
 assert header.strip()==b'# compiler: R8'
 digest.update(header)
 needles={s:s.encode() for s in symbols}
 for line in f:
  digest.update(line)
  for symbol,needle in needles.items():
   if needle in line:counts[symbol]+=1
print(json.dumps({'mappingSha256':digest.hexdigest(),'linesMentioningEachSymbol':{s:counts[s] for s in symbols}},indent=2))
if not all(counts[s]>0 for s in symbols[:2]) or any(counts[s] for s in symbols[2:]):
 raise SystemExit('Expected active subtitle paths and no retired UI mapping records')
