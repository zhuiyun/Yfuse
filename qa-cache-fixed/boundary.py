import json,pathlib,subprocess
p=pathlib.Path('qa-media')
rows=json.loads(subprocess.check_output(['ffprobe','-v','error','-select_streams','v:0','-show_packets','-show_entries','packet=pts_time,pos,size,flags','-of','json',str(p/'ProbeA.mp4')]))['packets']
data={}
for name,low,high in [('hotPackets',8,13),('coldPackets',142,144)]:
    data[name]=[[int(r['pos']),int(r['pos'])+int(r['size'])-1] for r in rows if low<=float(r['pts_time'])<=high]
    assert data[name]
data['before']=min(int(r['pos']) for r in rows if float(r['pts_time'])>=115)
assert max(b for a,b in data['hotPackets'])<data['before']<min(a for a,b in data['coldPackets'])
(p/'boundary.json').write_text(json.dumps(data))
