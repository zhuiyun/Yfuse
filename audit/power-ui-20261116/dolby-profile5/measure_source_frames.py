"""Inspect a small bounded sample on the source host. Return numeric decoder metadata only."""
import base64
import argparse
import datetime
import getpass
import inspect
import json
import os
from pathlib import Path
import shlex
import struct
import subprocess
import sys


def measure_file(location, size, opener):
    import tempfile
    import urllib.request
    mib = 1024 * 1024
    used = 0
    spans = []
    scratch = tempfile.TemporaryFile(dir='/dev/shm')
    fd = scratch.fileno()

    def copy_range(offset, length):
        nonlocal used
        length = min(length, size - offset)
        if length <= 0 or used + length > 24 * mib:
            raise ValueError('range budget')
        req = urllib.request.Request(location, headers={'Range': f'bytes={offset}-{offset+length-1}',
            'User-Agent': 'Emby for Android Mobile', 'Accept-Encoding': 'identity'})
        with opener.open(req, timeout=25) as response:
            if response.status != 206 or response.headers.get('Content-Range') != f'bytes {offset}-{offset+length-1}/{size}':
                raise ValueError('range response')
            block = response.read(length)
        if len(block) != length or os.pwrite(fd, block, offset) != length:
            raise ValueError('incomplete range')
        used += length
        spans.append((offset, offset+length))

    def fill_box(offset, length):
        end = offset + length
        for start, stop in sorted(spans):
            if stop <= offset or start >= end:
                continue
            if start > offset:
                copy_range(offset, start-offset)
            offset = max(offset, stop)
        if offset < end:
            copy_range(offset, end-offset)

    try:
        os.ftruncate(fd, size)
        copy_range(0, 4 * mib)
        if os.pread(fd, 4, 4) == b'ftyp':
            offset = 0
            # Locate top-level boxes by their declared lengths without scanning movie data.
            for _ in range(32):
                if offset >= size:
                    break
                if not any(a <= offset and offset+16 <= b for a,b in spans):
                    copy_range(offset, min(16, size-offset))
                header = os.pread(fd, 16, offset)
                length, kind = struct.unpack('>I4s', header[:8])
                if length == 1:
                    length, = struct.unpack('>Q', header[8:])
                if length == 0:
                    length = size - offset
                if length < 8 or offset + length > size:
                    raise ValueError('invalid box bounds')
                if kind == b'moov':
                    fill_box(offset, length)
                    break
                offset += length
        command = ['nice', '-n', '10', '/opt/emby-server/bin/ffprobe', '-v', 'error',
                   '-threads', '1', '-probesize', str(4*mib), '-analyzeduration','2000000',
                   '-select_streams', 'v:0', '-read_intervals', '%+#12', '-show_frames',
                   '-show_streams', '-of', 'json', '/proc/self/fd/'+str(fd)]
        env = dict(os.environ, LD_LIBRARY_PATH='/opt/emby-server/lib:/opt/emby-server/extra/lib')
        measured = subprocess.run(command, pass_fds=(fd,), stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                  timeout=30, env=env)
        data = json.loads(measured.stdout)
        stream = next(iter(data.get('streams', [])), {})
        container = next((s for s in stream.get('side_data_list', []) if s.get('side_data_type')=='DOVI configuration record'), {})
        observations = []
        for frame in data.get('frames', []):
            pos, count = int(frame.get('pkt_pos', -1)), int(frame.get('pkt_size', 0))
            if count <= 0 or not any(a <= pos and pos+count <= b for a,b in spans):
                continue
            dovi = next((s for s in frame.get('side_data_list', []) if s.get('side_data_type')=='Dolby Vision Metadata'), {})
            if not dovi:
                continue
            components = dovi.get('components', [])
            observations.append({'pts':frame.get('best_effort_timestamp'), 'width': frame.get('width'),
                'height':frame.get('height'), 'bitDepth':dovi.get('bl_bit_depth'),
                'outputDepth':dovi.get('vdr_bit_depth'),'coefficientBits':dovi.get('coef_log2_denom'),
                'fullRange':dovi.get('bl_video_full_range_flag'), 'colourSpace':dovi.get('signal_color_space'),
                'eotf':dovi.get('signal_eotf'), 'nonlinearQuantization':dovi.get('nlq_method_idc'),
                'partitions':[dovi.get('num_x_partitions'),dovi.get('num_y_partitions')],
                'methods':[sorted({p['mapping_idc'] for p in c.get('pieces', [])}) for c in components],
                'orders':[sorted({p.get('poly_order',p.get('mmr_order')) for p in c.get('pieces', [])}) for c in components]})
        return {'bytesFetched':used,'probeExit':measured.returncode,'profile':container.get('dv_profile'),
                'compatibilityId':container.get('dv_bl_signal_compatibility_id'),'frameMetadata':observations}
    finally:
        scratch.close()


def on_server():
    request = json.loads(sys.stdin.readline())
    namespace = {}
    exec(request.pop('resolver'), namespace)
    exec(request.pop('selection'), namespace)
    user, password = request.pop('credentials')
    session = namespace['call']('/Users/AuthenticateByName', {'Username':user,'Pw':password})
    namespace['TOKEN'] = session.pop('AccessToken')
    namespace['USER'] = session['User']['Id']
    del session, user, password
    sources = {}
    try:
        reference=request['reference']
        pairs = namespace['select_sources'](namespace, set(reference), reference)
        for label, entry in pairs:
            _, resolved, error=namespace['resolve']((label,entry))
            prior = reference[label]
            if error or not resolved or (resolved['size'], resolved['seconds'], resolved['container']) != (
                    prior['bytes'], prior['seconds'], prior['container']):
                raise ValueError('source identity')
            sources[label]=(resolved['location'],resolved['size'])
    finally:
        namespace['close_emby_session'](namespace)
        namespace['TOKEN']=None
    output={'loggedOutBeforeProbing':True,'files':{}}
    for label, (url,size) in sources.items():
        try:
            output['files'][label]=measure_file(url,size,namespace['HTTP'])
        except Exception as error:
            reason=str(error)
            allowed={'range budget','range response','incomplete range','invalid box bounds'}
            output['files'][label]={'errorType':type(error).__name__, 'reason':reason if reason in allowed else None}
    print(json.dumps(output))


if __name__=='__main__':
    if len(sys.argv)>1 and sys.argv[1]=='--server':
        on_server()
    else:
        from importlib.util import spec_from_file_location, module_from_spec
        root=Path(__file__).resolve().parents[3]
        audit=root/'audit/power-ui-20261116'
        labels=set(json.loads((audit/'private-verification-28.json').read_text())['reportedProfile5CompatibilityZero'])
        media=json.loads((audit/'private-originals-28-by-file.json').read_text())['media']
        reference={r['label']:{k:r[k] for k in ('kind','bytes','container','seconds')} for r in media if r['label'] in labels}
        assert set(reference)==labels and len(labels)==6
        parser=argparse.ArgumentParser()
        parser.add_argument('--label', choices=sorted(labels), action='append')
        options=parser.parse_args()
        if options.label:
            labels=set(options.label)
            reference={label:reference[label] for label in labels}
        spec=spec_from_file_location('session',root/'qa-private-relay/serve_session.py')
        session=module_from_spec(spec)
        spec.loader.exec_module(session)
        functions='import http.client, urllib.parse\n'+'\n'.join(inspect.getsource(f) for f in (
            session.catalog_identity,session.select_sources,session.close_emby_session))
        source=(root/'audit/legacy-engine-20261113/integrated-20261116/source_bridge_entry.py').read_text().split("\nstage = 'config'",1)[0]
        head='import json,os,struct,subprocess,sys\n'
        program=head+inspect.getsource(measure_file)+'\n'+inspect.getsource(on_server)+'\non_server()'
        encoded=base64.b64encode(program.encode()).decode()
        command="exec(__import__('base64').b64decode("+repr(encoded)+"))"
        credentials=json.loads(getpass.getpass('Private Emby input: '))
        config=dict(reference=reference,credentials=credentials,resolver=source,selection=functions)
        job=subprocess.run(['ssh','-T','-o','BatchMode=yes','-o','StrictHostKeyChecking=yes',
            '-o','ConnectTimeout=8','--', os.environ['YFUSE_QA_SSH_TARGET'],'python3','-c',shlex.quote(command)],
            input=json.dumps(config),capture_output=True,text=True,timeout=900)
        del credentials,config
        if job.returncode:
            raise SystemExit('Source-host metadata probe did not complete')
        result=json.loads(job.stdout)
        assert result['loggedOutBeforeProbing'] and set(result['files'])==labels
        result['checkedUtc']=datetime.datetime.now(datetime.timezone.utc).isoformat()
        result['scope']='server-local samples of early decoded frames; only numeric metadata returned'
        path=audit/'dolby-profile5/private-frame-metadata.json'
        if options.label and path.exists():
            prior=json.loads(path.read_text())
            prior.setdefault('rechecks',[]).append({'checkedUtc':result['checkedUtc'],'labels':sorted(labels),
                'before':{label:prior['files'][label] for label in labels}})
            prior['files'].update(result['files'])
            prior['checkedUtc']=result['checkedUtc']
            result=prior
        path.write_text(json.dumps(result,indent=2)+'\n',encoding='utf8')
        print({k:len(v.get('frameMetadata',[])) for k,v in result['files'].items()})
