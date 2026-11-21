"""Loopback-only synthetic media server for the disposable Yfuse UI audit."""
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import sys
from urllib.parse import parse_qs, urlsplit

ROOT = Path(sys.argv[1])
USER = {"Name":"Audit","Id":"11111111111111111111111111111111","HasPassword":False}
INFO = {"Id":"a"*32,"ServerName":"Yfuse Design Lab","Version":"4.8.0.0","ProductName":"Emby Server","LocalAddress":"http://127.0.0.1:8096"}
VIEW = {"Id":"lib01","Name":"Audit Library","Type":"CollectionFolder","CollectionType":"movies","IsFolder":True}
URL = "http://127.0.0.1:8096/Videos/m01/stream.mp4"
TRACKS = [{"Type":"Video","Index":0,"Codec":"h264","Width":640,"Height":360},{"Type":"Audio","Index":1,"Codec":"aac","Language":"eng","DisplayTitle":"English","Channels":1},{"Type":"Subtitle","Index":2,"Codec":"srt","Language":"chi","DisplayTitle":"中文（审查样片）","IsExternal":True,"DeliveryMethod":"External","DeliveryUrl":"/Videos/m01/m01/Subtitles/2/Stream.srt"}]
SOURCE = {"Id":"m01","Path":"Audit Film.mp4","Protocol":"File","Type":"Default","Container":"mp4","SupportsDirectPlay":True,"SupportsDirectStream":True,"RunTimeTicks":900000000,"MediaStreams":TRACKS,"DirectStreamUrl":URL,"Size":(ROOT/"clip.mp4").stat().st_size}
MOVIE = {"Id":"m01","Name":"Audit Film","Type":"Movie","Overview":"A local 90-second motion and subtitle sample created for this UI review.","ProductionYear":2026,"CommunityRating":8.2,"RunTimeTicks":900000000,"MediaType":"Video","UserData":{"Played":False,"PlaybackPositionTicks":80000000},"MediaSources":[SOURCE],"ImageTags":{"Primary":"p01"},"BackdropImageTags":["bd01"],"Genres":["Demo"],"People":[{"Id":"p01","Name":"Audit Actor","Type":"Actor","Role":"Test subject"}]}
SERIES = dict(MOVIE,Id="s01",Name="Audit Series",Type="Series",IsFolder=True,ChildCount=1)
EPISODE = dict(MOVIE,Id="e01",Name="First episode",Type="Episode",SeriesId="s01",SeriesName="Audit Series",SeasonId="se01",IndexNumber=1,ParentIndexNumber=1)
ITEMS = [MOVIE,SERIES]
class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args): pass
    def reply(self, data, typ="application/json", status=200):
        if typ=="application/json": data=json.dumps(data).encode()
        self.send_response(status); self.send_header("Content-Type",typ); self.send_header("Content-Length",str(len(data))); self.end_headers()
        if self.command!="HEAD": self.wfile.write(data)
    def do_HEAD(self): self.do_GET()
    def do_POST(self):
        self.rfile.read(int(self.headers.get("Content-Length","0")))
        self.do_GET()
    def do_GET(self):
        parsed=urlsplit(self.path); p=parsed.path.lower().rstrip("/"); q={k.lower():v[0] for k,v in parse_qs(parsed.query).items()}
        if p.startswith("/emby"): p=p[5:]
        if "authenticatebyname" in p: return self.reply({"User":USER,"AccessToken":"local-audit-only","ServerId":INFO["Id"]})
        if p.endswith("/users/public"): return self.reply([USER])
        if "system/info" in p or p.endswith("/branding/configuration"):return self.reply(INFO)
        if "/images/" in p:
            data=(ROOT/"poster.jpg"); return self.reply(data.read_bytes(),"image/jpeg") if data.exists() else self.reply({},status=404)
        if "/subtitles/" in p: return self.reply(b"1\n00:00:00,000 --> 00:01:29,000\nDesign audit subtitle\n","text/plain")
        if "stream" in p or p.endswith(".mp4"):
            data=ROOT/"clip.mp4"; total=data.stat().st_size
            import re
            m=re.match(r"bytes=(\d+)-(\d*)",self.headers.get("Range",""));lo=int(m[1]) if m else 0; hi=min(total-1,int(m[2])) if m and m[2] else total-1
            self.send_response(206 if m else 200); self.send_header("Content-Type","video/mp4");self.send_header("Accept-Ranges","bytes");self.send_header("Content-Length",str(hi-lo+1))
            if m:self.send_header("Content-Range",f"bytes {lo}-{hi}/{total}")
            self.end_headers()
            if self.command!="HEAD":
                with data.open("rb") as f:f.seek(lo);self.wfile.write(f.read(hi-lo+1))
            return
        if p.endswith("/playbackinfo"):return self.reply({"MediaSources":[SOURCE],"PlaySessionId":"audit-session"})
        if p.endswith("/views") or "virtualfolders" in p: result=[VIEW]
        elif p.endswith("/seasons"):result=[{"Id":"se01","Name":"Season 1","SeriesId":"s01","Type":"Season","IndexNumber":1}]
        elif p.endswith("/episodes") or p.endswith("/nextup"):result=[EPISODE]
        elif p.endswith("/latest"):return self.reply(ITEMS)
        elif p.endswith("/counts"):return self.reply({"MovieCount":1,"SeriesCount":1})
        elif p.endswith("/m01"):return self.reply(MOVIE)
        elif p.endswith("/s01"):return self.reply(SERIES)
        elif p.endswith("/lib01"):return self.reply(VIEW)
        elif p.endswith("/p01"):return self.reply({"Id":"p01","Name":"Audit Actor","Type":"Person"})
        elif p.endswith("/e01"):return self.reply(EPISODE)
        elif p.endswith("/users/"+USER["Id"]):return self.reply(USER)
        elif p.endswith("/users"):return self.reply([USER])
        elif "/items" in p or "similar" in p:result=ITEMS
        else:result=[]
        return self.reply({"Items":result,"TotalRecordCount":len(result)})
ThreadingHTTPServer(("127.0.0.1",8096),Handler).serve_forever()
