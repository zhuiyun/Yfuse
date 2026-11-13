import json,math,os,pathlib,subprocess,sys,urllib.parse
from PIL import Image,ImageDraw
root=pathlib.Path('qa-media')
root.mkdir(exist_ok=True)
out=pathlib.Path('results')
out.mkdir(exist_ok=True)
shard,shards=map(int,sys.argv[1:3])
catalog=json.loads(pathlib.Path('qa-titles/catalog.json').read_text())
specs=[
    ('h264','mp4','video/mp4','aac',480,22),('hevc','mkv','video/x-matroska','ac3',960,24),
    ('vp9','webm','video/webm','libopus',640,33),('h264','mov','video/quicktime','aac',1280,19),
    ('mpeg4','mp4','video/mp4','mp3',640,6),('h264','ts','video/mp2t','aac',960,20),
    ('h264','mkv','video/x-matroska','flac',1280,17),('hevc','mp4','video/mp4','aac',640,27)]
def metadata(path):
    args=['ffprobe','-v','error','-rw_timeout','20000000','-probesize','5000000','-analyzeduration','4000000','-show_entries','format=duration,size,format_name:stream=codec_type,codec_name,width,height,channels','-of','json',str(path)]
    x=subprocess.run(args,capture_output=True,timeout=65,check=True)
    data=json.loads(x.stdout)
    return {'seconds':float(data['format']['duration']),'bytes':int(data['format'].get('size',0)),'format':data['format']['format_name'],'streams':data['streams']}
files=[]
problems=[]
for index,item in enumerate(catalog):
    if index%shards!=shard:continue
    try:
        if urllib.parse.urlsplit(item['url']).hostname not in ('video.blender.org','images-assets.nasa.gov'):raise ValueError('unlisted media host')
        src=metadata(item['url'])
        duration=min(98+index%12,max(0,math.floor(src['seconds']-.75)))
        if duration<19:raise ValueError('clip too short')
        start=max(0,math.floor((src['seconds']-duration-0.5)*(.12+index%5*.13)))
        vc,ext,mime,ac,width,quality=specs[index%len(specs)]
        height=width*9//16
        name=f'title-{index:03}.{ext}';dst=root/name
        # The marker occupies only the central strip. All other picture and audio samples come from this work.
        rate=8
        marker=root/'timecode.rgb'
        with marker.open('wb') as f:
            for frame in range(duration*rate):
                bits=[(frame>>b)&1 for b in range(10,-1,-1)]
                pattern=[1,0,1]+bits+[sum(bits)%2,0,1,0]
                im=Image.new('L',(216,29));draw=ImageDraw.Draw(im)
                for i,bit in enumerate(pattern):
                    if bit:draw.rectangle((i*12,0,i*12+11,28),fill=255)
                f.write(im.tobytes())
        opts={'h264':['-c:v','libx264','-preset','ultrafast','-crf',str(quality)],'hevc':['-c:v','libx265','-preset','ultrafast','-crf',str(quality),'-x265-params','pools=2:frame-threads=1:log-level=error'],'vp9':['-c:v','libvpx-vp9','-deadline','realtime','-cpu-used','8','-crf',str(quality),'-b:v','0'],'mpeg4':['-c:v','mpeg4','-q:v',str(quality)]}[vc]
        graph=(f'[0:v]fps=16,scale={width}:{height}:force_original_aspect_ratio=decrease,pad={width}:{height}:(ow-iw)/2:(oh-ih)/2,setsar=1[film];'
               f'[1:v]scale={round(216*width/320)}:{round(29*height/180)}:flags=neighbor[clock];[film][clock]overlay={round(52*width/320)}:{round(74*height/180)}:shortest=1[out]')
        args=['ffmpeg','-nostdin','-v','error','-y','-rw_timeout','20000000','-threads','2','-ss',str(start),'-i',item['url'],'-f','rawvideo','-pixel_format','gray','-video_size','216x29','-framerate','8','-i',str(marker),'-filter_complex_threads','2','-filter_complex',graph,'-map','[out]','-map','0:a:0?','-t',str(duration),'-g','16','-pix_fmt','yuv420p','-threads','2']+opts+['-c:a',ac]
        if ac!='flac':args+=['-b:a','96k']
        if ext in ('mov','mp4'):args+=['-movflags','+faststart']
        if ext=='ts':args+=['-muxdelay','0','-muxpreload','0']
        subprocess.run(args+[str(dst)],check=True,timeout=360)
        actual=metadata(dst);duration=math.floor(actual['seconds'])
        files.append(dict(name=name,duration=duration,size=dst.stat().st_size(),codec=vc,mime=mime,width=width,titleID=item['id'],kind=item['kind'],sourcePage=item['page'],startInOriginal=start,original=src,testFile=actual))
        marker.unlink(missing_ok=True)
        print('PREPARED',index,item['kind'],src['bytes'],name,dst.stat().st_size(),duration,flush=True)
    except (OSError,ValueError,KeyError,subprocess.SubprocessError) as ex:
        problems.append({'id':item['id'],'reason':type(ex).__name__});print('PREPARE_FAILED',index,type(ex).__name__,flush=True)
    (root/'fixtures.json').write_text(json.dumps(files))
    (out/'source-preparation.json').write_text(json.dumps({'shard':shard,'prepared':[f['titleID'] for f in files],'failed':problems}))
if not files:raise SystemExit(2)
