from pathlib import Path
from PIL import Image
from collections import Counter
import json,sys
p=Path(sys.argv[1]);rows={}
for name in ['05b-discovery','07-landscape','08-dark','layout-font-1.0-landscape-10s','layout-font-1.3-landscape-10s','layout-tablet-viewport']:
 path=p/(name+'.png')
 if not path.exists():rows[name]={'ok':False,'error':'screenshot missing'};continue
 im=Image.open(path).convert('RGB');w,h=im.size;area=im.crop((4,2,min(250,w//4),min(52,int(min(w,h)*.065))));counts=Counter(area.getdata());bg=counts.most_common(1)[0][0]
 def luminance(rgb):
  a=[v/255 for v in rgb];a=[v/12.92 if v<=.04045 else ((v+.055)/1.055)**2.4 for v in a];return a[0]*.2126+a[1]*.7152+a[2]*.0722
 base=luminance(bg)
 def ratio(col):
  val=luminance(col);return (max(val,base)+.05)/(min(val,base)+.05)
 contrasting=sum(num for col,num in counts.items() if ratio(col)>=3.0)
 rows[name]={'background':bg,'highContrastPixels':contrasting,'ok':contrasting>=40}
(p/'bar-contrast.json').write_text(json.dumps(rows,indent=2))
print(rows)
sys.exit(int(any(not d['ok'] for d in rows.values())))
