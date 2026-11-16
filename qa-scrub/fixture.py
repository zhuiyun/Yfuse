import subprocess as sp, pathlib, sys
from PIL import Image,ImageDraw,ImageFont
def create(path,seconds,clip):
 w,h,rate=320,180,8
 tone='523' if clip=='A' else '880'
 cmd=['ffmpeg','-loglevel','error','-y','-f','rawvideo','-pix_fmt','rgb24','-s','320x180','-r',str(rate),'-i','-','-f','lavfi','-i','sine=frequency='+tone+':sample_rate=44100','-t',str(seconds),'-c:v','libx264','-pix_fmt','yuv420p','-threads','2','-preset','ultrafast','-g','16','-c:a','aac','-b:a','64k','-movflags','+faststart',path]
 p=sp.Popen(cmd,stdin=sp.PIPE);font=ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf',25)
 for n in range(seconds*rate):
  im=Image.new('RGB',(w,h),(22,60,85) if clip=='A' else (70,38,60));d=ImageDraw.Draw(im)
  d.text((64,30),f'{clip}  {n//rate//60:02}:{n//rate%60:02}.{n%rate*125:03}',font=font,fill='white')
  bits=[(n>>b)&1 for b in range(10,-1,-1)]
  pattern=[1,0,1]+bits+[sum(bits)%2,0,1,0]
  for i,b in enumerate(pattern):d.rectangle((52+12*i,74,63+12*i,102),fill='white' if b else 'black')
  d.rectangle((20,131,20+n%(w-40),138),fill='orange');p.stdin.write(im.tobytes())
 p.stdin.close()
 if p.wait():raise RuntimeError('ffmpeg failed')
if __name__=='__main__':
 folder=pathlib.Path(sys.argv[1]);folder.mkdir(exist_ok=True);create(str(folder/'ProbeA.mp4'),150,'A');create(str(folder/'ProbeB.mp4'),105,'B')
