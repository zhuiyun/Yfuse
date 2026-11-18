"""Compare standard resume sequences on the disposable receiver, independently of either phone core."""
import hashlib
import http.client
import ipaddress
import json
import os
from pathlib import Path
import re
import time
from urllib.parse import urlsplit
import xml.etree.ElementTree as ET

from cast_receiver import docker_value, latest_picture, read_picture, stable_picture
from media import MediaServer

AVT = 'urn:schemas-upnp-org:service:AVTransport:1'
RESULTS = Path('results/cast/protocol-control')


class Transport:
    def __init__(self, url, peer):
        parsed = urlsplit(url)
        if parsed.scheme != 'http' or parsed.hostname != peer or not ipaddress.ip_address(peer).is_private:
            raise ValueError('Expected the control service on the dedicated private renderer')
        self.peer, self.port, self.path = peer, parsed.port or 80, parsed.path
        self.commands = []

    def send(self, action, **arguments):
        envelope = ET.Element('{http://schemas.xmlsoap.org/soap/envelope/}Envelope')
        body = ET.SubElement(envelope, '{http://schemas.xmlsoap.org/soap/envelope/}Body')
        command = ET.SubElement(body, '{'+AVT+'}'+action)
        for name,value in {'InstanceID':'0', **arguments}.items():
            ET.SubElement(command, name).text = str(value)
        conn = http.client.HTTPConnection(self.peer, self.port, timeout=5)
        started = time.monotonic()
        try:
            conn.request('POST', self.path, ET.tostring(envelope, encoding='utf-8', xml_declaration=True),
                         {'Content-Type':'text/xml; charset="utf-8"', 'SOAPACTION':f'"{AVT}#{action}"'})
            response = conn.getresponse()
            data = response.read(65537)
        finally:
            conn.close()
        if response.status != 200 or len(data)>65536:
            raise AssertionError(f'Renderer returned {response.status} for {action}')
        reply = ET.fromstring(data).find('.//{*}'+action+'Response')
        if reply is None:
            raise AssertionError('Renderer did not acknowledge '+action)
        values = {node.tag.rsplit('}',1)[-1]: node.text for node in reply}
        if action != 'GetPositionInfo':
            self.commands.append({'action':action, 'target':arguments.get('Target'), 'monotonic':started})
        return values

    def seek(self, second):
        hours, remainder = divmod(second, 3600)
        minutes, seconds = divmod(remainder, 60)
        self.send('Seek', Unit='REL_TIME', Target=f'{hours:02d}:{minutes:02d}:{seconds:02d}')

    def position(self):
        value = self.send('GetPositionInfo').get('RelTime') or ''
        fields = value.split(':')
        return sum(int(v)*m for v,m in zip(fields,(3600,60,1))) if len(fields)==3 and all(v.isdigit() for v in fields) else None


def advancing(frames, low, high, after=-1):
    first = None
    stop = time.monotonic()+15
    while time.monotonic()<stop:
        frame = latest_picture(frames)
        if frame and frame[0]>after and low<=frame[1]<=high:
            if first is None:
                first=frame[:2]
            elif frame[0]>first[0] and frame[1]>first[1]:
                return frame[0],frame[1]
        time.sleep(.07)
    raise AssertionError('No decoded progress in the expected interval '+str((low,high)))


def run_case(transport, uri, frames, mode, details):
    transport.send('Stop')
    assert transport.send('GetTransportInfo')['CurrentTransportState']=='STOPPED'
    for path in frames.glob('frame-*.rgb'):
        assert re.fullmatch(r'frame-\d{8}\.rgb',path.name) and path.is_file() and not path.is_symlink()
        path.unlink()
    transport.commands.clear()
    transport.send('SetAVTransportURI',CurrentURI=uri,CurrentURIMetaData='')
    transport.send('Play',Speed=1)
    first,_=advancing(frames,0,9)
    transport.seek(60)
    forward,_=advancing(frames,59,66,first)
    transport.seek(20)
    advancing(frames,19,26,forward)
    transport.send('Pause')
    paused_at=time.monotonic()
    still=stable_picture(frames)
    assert 19<=still['second']<=28
    while time.monotonic()-paused_at<17:
        time.sleep(.15)
    assert transport.send('GetTransportInfo')['CurrentTransportState']=='PAUSED_PLAYBACK'
    last=latest_picture(frames)
    assert last and last[:2]==(still['frame'],still['second'])
    anchor=last[1]
    details.update(pausedPicture=anchor, reportedWhilePaused=transport.position())
    if mode=='paused-seek-play':
        transport.seek(anchor)
        # A seek can preroll a paused stream. Record it separately from any post-Play frame.
        time.sleep(1.1)
        assert transport.send('GetTransportInfo')['CurrentTransportState']=='PAUSED_PLAYBACK'
    last=latest_picture(frames)
    assert last
    details['immediatelyBeforePlay']={'frame':last[0],'second':last[1]}
    started=time.monotonic()
    transport.send('Play',Speed=1)
    if mode=='play-seek':
        transport.seek(anchor)
    if mode=='play-observe-seek':
        deadline=started+2
        reported=[]
        while time.monotonic()<deadline:
            pos=transport.position()
            reported.append([round(time.monotonic()-started,3),pos])
            if pos is not None and abs(pos-anchor)>5:
                break
            time.sleep(.08)
        details['reportedBeforeCorrectiveSeek']=reported
        transport.seek(anchor)
    timeline=[]
    details['frames']=timeline
    next_frame=last[0]+1
    while time.monotonic()-started<16 and len(timeline)<6:
        frame=read_picture(frames/f'frame-{next_frame:08d}.rgb')
        if frame is None:
            time.sleep(.06)
            continue
        _,sec,picture=frame
        elapsed=round(time.monotonic()-started,3)
        name=f'{mode}-{len(timeline)}.png'
        picture.save(RESULTS/name)
        timeline.append({'index':next_frame,'second':sec,'observedAfter':elapsed,'file':name,
                         'sha256':hashlib.sha256((RESULTS/name).read_bytes()).hexdigest()})
        next_frame+=1
    assert len(timeline)==6
    details.update(firstAtAnchor=anchor-1<=timeline[0]['second']<=anchor+2+timeline[0]['observedAfter'],
                   lastNearAnchor=anchor-1<=timeline[-1]['second']<=anchor+2+timeline[-1]['observedAfter'],
                   reportedAfter=transport.position(), commands=transport.commands.copy())


def main():
    assert os.environ.get('GITHUB_ACTIONS')=='true'
    results=Path('results/cast')
    peer=docker_value('inspect','qa-receiver','--format','{{.NetworkSettings.Networks.bridge.IPAddress}}')
    bridge=docker_value('network','inspect','bridge','--format','{{(index .IPAM.Config 0).Gateway}}')
    control=json.loads((results/'discovery-control.json').read_text())
    assert control['friendlyName']=='Yfuse QA Renderer' and AVT in control['services']
    transport=Transport(control['avTransportUrl'],peer)
    record=json.loads((results/'outcome.json').read_text())
    tmp=Path(os.environ['RUNNER_TEMP']).resolve()
    fixture=tmp/'cast-clock.mp4'
    frames=(tmp/'cast-frames').resolve()
    assert frames.parent==tmp and hashlib.sha256(fixture.read_bytes()).hexdigest()==record['fixtureSha256']
    RESULTS.mkdir(exist_ok=True)
    study={'fixtureSha256':record['fixtureSha256'],'clock':os.environ['QA_RECEIVER_CLOCK'],
           'appForceStopped':True,'cases':{}}
    server=MediaServer(fixture,RESULTS/'http.jsonl',bridge_address=bridge)
    try:
        for mode in ('play','play-seek','paused-seek-play','play-observe-seek'):
            info={}
            study['cases'][mode]=info
            try:
                run_case(transport,f'http://{bridge}:18389/clock.mp4',frames,mode,info)
            except Exception as e:
                info['testError']=type(e).__name__+': '+str(e)
            (RESULTS/'measurements.json').write_text(json.dumps(study,indent=2))
        assert all('testError' not in c for c in study['cases'].values())
    finally:
        try:
            transport.send('Stop')
        finally:
            server.close()


if __name__=='__main__':
    main()
