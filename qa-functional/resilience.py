import asyncio
import json
import random
import shlex
import sys
import threading
import time
from pathlib import Path

from ext_run import adb, marker, rail, screen, tap
from features import coords, find, page_info, snapshot

PKG = "com.yfuse"
ACTIVITY = PKG + "/com.yfuse.feature.player.ExternalPlaybackActivity"
OUT = Path("results")
SOCKET, API, SEED = sys.argv[1:]
RNG = random.Random(int(SEED))
MEDIA = json.loads(Path("qa-emby-local/streams.json").read_text())
DURATION = {"ProbeA": 150, "ProbeB": 105}
ROWS = []


class Relay:
    """Only this emulator's loopback media connection is interrupted."""
    def __init__(self):
        self.loop = asyncio.new_event_loop()
        self.peers = set()
        self.blocked = False
        self.rejected = 0
        self.connections = 0
        self.received = 0
        thread = threading.Thread(target=self.loop.run_forever, daemon=True)
        thread.start()
        self.call(self.listen())

    def call(self, operation):
        return asyncio.run_coroutine_threadsafe(operation, self.loop).result(timeout=8)

    async def listen(self):
        self.server = await asyncio.start_server(self.accept, "127.0.0.1", 18196)

    async def switch(self, block):
        self.blocked = block
        closed = len(self.peers) if block else 0
        if block:
            for writer in tuple(self.peers):
                writer.transport.abort()
        return {"closed": closed, "rejected": self.rejected, "bytes": self.received, "connections": self.connections}

    async def accept(self, peer, client):
        if self.blocked:
            self.rejected += 1
            client.transport.abort()
            return
        server = None
        transfers = []
        try:
            source, server = await asyncio.open_unix_connection(SOCKET)
            if self.blocked:
                self.rejected += 1
                return
            self.connections += 1
            self.peers.add(client)
            async def pump(reader, writer, downstream):
                while True:
                    chunk = await reader.read(65536)
                    if not chunk:
                        return
                    writer.write(chunk)
                    await writer.drain()
                    if downstream:
                        self.received += len(chunk)
            transfers = [asyncio.create_task(pump(peer, server, False)), asyncio.create_task(pump(source, client, True))]
            await asyncio.wait(transfers, return_when=asyncio.FIRST_COMPLETED)
        except (OSError, asyncio.CancelledError):
            pass
        finally:
            for task in transfers:
                task.cancel()
            if transfers:
                await asyncio.gather(*transfers, return_exceptions=True)
            self.peers.discard(client)
            for writer in (client, server):
                if writer:
                    writer.close()


def state():
    return adb("shell", "pidof", PKG, check=False).strip()


def record(label, **detail):
    ROWS.append(dict(case=label, **detail))
    (OUT / "measurements.json").write_text(json.dumps({"api": API, "seed": int(SEED), "cases": ROWS}, indent=2))
    print("CASE", label, json.dumps(detail), flush=True)


def save_frame(im, label):
    small = im.copy()
    small.thumbnail((650, 650))
    small.save(OUT / (label + ".png"))


def open_url(url, kind):
    mime = "video/mp4" if kind == "url" else "video/mpegurl"
    adb("shell", "am start -a android.intent.action.VIEW -n " + ACTIVITY + " -t " + mime + " -d " + shlex.quote(url))


def allow_open(tree):
    button = find(tree, ["打开", "Open", "确认打开", "仍然打开", "知道了", "Got it", "确定", "OK"])
    if button is not None:
        tap(*coords(button))
        return True
    return False


def frame_time(im, clip):
    pts = marker(im)
    if pts is None:
        return None
    # The two independently generated films use blue and maroon backgrounds.
    w, h = im.size
    scale = min(w / 320, h / 180)
    left, top = (w - 320 * scale) / 2, (h - 180 * scale) / 2
    colors = [im.getpixel((int(left + x * scale), int(top + y * scale))) for x,y in ((29,65),(272,65),(28,112),(270,112))]
    votes = sum((blue-red > 8 if clip == "ProbeA" else red-blue > 3) for red,green,blue in colors)
    return pts if votes >= 2 else None


def advancing(clip, label, target=None, timeout=50, allow_dialog=False, landed_after=2.5, already_elapsed=0):
    begin = time.monotonic()
    first = None
    observations = []
    confirmed = None
    while time.monotonic() - begin < timeout:
        im = screen()
        elapsed = time.monotonic() - begin
        v = frame_time(im, clip)
        if v is not None:
            observations.append([round(elapsed, 2), v])
            inside = target is None or target - 2.1 <= v <= target + elapsed + already_elapsed + 1.8
            if inside:
                if first is None or v < first[1] or v - first[1] > elapsed - first[0] + 2.25:
                    first = (elapsed, v)
                    confirmed = None
                elif v >= first[1] + 0.375 and v - first[1] <= elapsed - first[0] + 2.25:
                    if confirmed is None:
                        confirmed = (elapsed, v)
                    if elapsed - confirmed[0] >= landed_after and v >= confirmed[1] + max(.25, landed_after * .45):
                        return {"first": first[1], "later": v, "firstSec": round(first[0],2), "checkSec": round(elapsed,2)}
            elif first:
                first = confirmed = None
        elif allow_dialog and int(elapsed) % 3 == 0:
            allow_open(snapshot())
        time.sleep(.24)
    save_frame(screen(), "failed-" + label)
    (OUT / ("observations-" + label + ".json")).write_text(json.dumps(observations))
    raise AssertionError("no advancing decoded frames at requested position")


def show_rail():
    for i in range(6):
        tree = snapshot()
        r = rail(tree)
        if r:
            return r
        if not allow_open(tree):
            im = screen()
            w,h = im.size
            tap(int(w*.52),int(h*.36))
        time.sleep(.42)
    raise AssertionError("visible playback seek bar unavailable")


def issue(r, fractions, gesture_ms=150):
    x0,y0,x1,y1 = r
    y = (y0+y1)//2
    def x(f):
        return round(x0 + (x1-x0-1)*f)
    cmds = [f"input swipe {x(.18 if f>.5 else .81)} {y} {x(f)} {y} {gesture_ms}" for f in fractions]
    started = time.monotonic()
    adb("shell", " && ".join(cmds))
    return round(time.monotonic()-started,2)


def uninterrupted(clip, label, seconds):
    start_im = screen()
    start = frame_time(start_im,clip)
    if start is None:
        raise AssertionError("missing starting frame")
    begin = time.monotonic()
    samples = []
    for i in range(1,1+seconds//5):
        time.sleep(max(0,begin+i*5-time.monotonic()))
        im = screen()
        v = frame_time(im,clip)
        elapsed=time.monotonic()-begin
        samples.append([round(elapsed,2),v])
        if v is None or abs(v-start-elapsed)>4 or (len(samples)>1 and v<=samples[-2][1]):
            save_frame(im,"failed-"+label)
            raise AssertionError("decoded picture stopped or drifted during sustained play")
    save_frame(im,label)
    return {"start":start,"samples":samples,"seconds":seconds}


def suite(relay):
    def captured(label, operation):
        try:
            data=operation()
            record(label,passed=True,**(data or {}))
            return True
        except Exception as e:
            record(label,passed=False,reason=str(e)[:160] if isinstance(e,AssertionError) else type(e).__name__)
            return False

    def back():
        adb("shell","input","keyevent","4")
        time.sleep(.65)

    def launch(clip, kind, label):
        open_url(MEDIA[clip][kind],kind)
        return advancing(clip,label,allow_dialog=True)

    def control_position():
        import re
        tree=snapshot()
        r=rail(tree)
        for node in tree.iter("node"):
            words=node.get("content-desc","")
            if "播放进度" not in words:continue
            times=re.findall(r"(?<!\d)(?:(?:\d{1,2}:)?\d{1,3}:\d\d)(?!\d)",words)
            if times:
                value=0
                for number in times[0].split(":"):value=60*value+int(number)
                return value
        return None

    def cut_case(seconds):
        # Reset to a visibly verified reference for both isolated HLS cases.
        r=show_rail()
        issue(r,[.62],320)
        advancing("ProbeA","anchor-"+str(seconds),93,timeout=25)
        r=show_rail()
        pre=frame_time(screen(),"ProbeA")
        pre_rail=control_position()
        before=relay.call(relay.switch(seconds>0))
        target=.14 if seconds<10 else .78
        try:
            issued=issue(r,[target],320)
            sent_at=time.monotonic()
            during=[]
            while time.monotonic()-sent_at<seconds:
                p=frame_time(screen(),"ProbeA")
                during.append([round(time.monotonic()-sent_at,2),p])
                time.sleep(.25)
        finally:
            after=relay.call(relay.switch(False))
        early=frame_time(screen(),"ProbeA")
        label="hls-"+("control" if seconds==0 else "outage-"+str(seconds))
        # The player can keep going on cached data during the cut. Its clock
        # must still trace back to the requested landing point.
        elapsed_from_seek=time.monotonic()-sent_at
        proof={"target":round(150*target,2),"pre":pre,"uiBefore":pre_rail,"box":r,"issued":issued,"closed":before["closed"],"refused":after["rejected"]-before["rejected"],"vBeforeRestore":during,"firstAfter":early}
        record(label+"-input",**proof)
        try:
            data=advancing("ProbeA",label,150*target,timeout=28,already_elapsed=elapsed_from_seek)
        finally:
            resumed=relay.call(relay.switch(False))
            record(label+"-transport",servedAfter=resumed["bytes"]-after["bytes"],uiAfter=control_position())
        if seconds and not (before["closed"] or after["rejected"]>before["rejected"]):
            raise AssertionError("no interrupted or rejected media connection; this instance was fully buffered")
        return data

    if captured("hls-open",lambda:launch("ProbeA","hls","open-hls")):
        for gap in (0,4,12):
            captured("hls-seek-"+str(gap),lambda gap=gap:cut_case(gap))
        def hls_long():
            issue(show_rail(),[.10],320)
            advancing("ProbeA","hls-long-anchor",15,timeout=23)
            return uninterrupted("ProbeA","hls-long",100)
        captured("hls-long",hls_long)
    back()
    if captured("direct-open",lambda:launch("ProbeB","url","open-direct")):
        for i in range(1,9):
            targets=[RNG.uniform(.12,.78) for _ in range(3)]
            def triplet(i=i,targets=targets):
                issued=issue(show_rail(),targets)
                return dict(target=round(targets[-1]*105,2),issued=issued,**advancing("ProbeB","direct-"+str(i),targets[-1]*105,timeout=20))
            captured("direct-burst-"+str(i),triplet)
        def direct_long():
            issue(show_rail(),[.12],320)
            advancing("ProbeB","long-anchor",12.6,timeout=25)
            return uninterrupted("ProbeB","direct-long",65)
        captured("direct-long",direct_long)
    def handle_error(i):
        pid=state()
        back()
        open_url("http://127.0.0.1:18196/qa-missing-video.mp4","url")
        deadline=time.monotonic()+35
        while time.monotonic()<deadline:
            tree=snapshot()
            if allow_open(tree):continue
            words=page_info(tree)
            if any(s.lower() in w.lower() for w in words for s in ("播放失败","重新播放","重试","无法播放","打开失败","404","无法读取","retry","playback error")):break
            time.sleep(.5)
        else:raise AssertionError("visible error missing")
        back()
        clip="ProbeA" if i%2 else "ProbeB"
        result=launch(clip,"url","error-recover-"+str(i))
        if not pid or pid!=state():raise AssertionError("player process changed")
        return dict(sameProcess=True,**result)
    for i in range(1,4):
        captured("error-next-"+str(i),lambda i=i:handle_error(i))
    if any(row.get("passed") is False for row in ROWS):
        raise AssertionError("one or more independent scenarios failed")


if __name__ == "__main__":
    OUT.mkdir(exist_ok=True)
    service = Relay()
    try:
        suite(service)
    except Exception as e:
        record("failure",error=type(e).__name__,message=str(e)[:180] if isinstance(e,AssertionError) else "test infrastructure exception")
        save_frame(screen(),"failure-final")
        raise
