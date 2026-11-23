# Local launcher; credentials are accepted only on stdin and sent over SSH stdin.
import os
import base64,datetime,getpass,hashlib,json,subprocess,shlex,sys
from pathlib import Path
root=Path(__file__).resolve().parents[3]
base=Path(__file__).resolve().parent
if len(sys.argv) == 2:
 config=json.loads(Path(sys.argv[1]).read_text(encoding='utf-8'))
 if 'credentials' in config:
  raise ValueError('The local configuration may contain only non-secret run parameters')
else:
 config=json.loads(sys.stdin.readline())
run=str(config['run'])
attempt=str(config.pop('attempt', 1))
if not run.isdecimal() or not attempt.isdecimal() or int(attempt) < 1:
 raise ValueError('Invalid workflow run or attempt number')
key_files={str(k):Path(v) for k,v in config.pop('keyFiles').items()}
expected_digests=config.pop('keyDigests',None)
if expected_digests and (set(expected_digests)!=set(key_files) or any(hashlib.sha256(path.read_bytes()).hexdigest()!=expected_digests[shard] for shard,path in key_files.items())):
 raise ValueError('A runner public key differs from the verified artifact')
keys={k:v.read_text(encoding='utf-8') for k,v in key_files.items()}
if set(keys) != {'0', '1'}:
 raise ValueError('Both current runner keys are required')
if any((root/'qa-private-arm-input'/(run+'-'+shard+'-'+attempt+'.json')).exists() for shard in keys):
 raise ValueError('An envelope already exists for this attempt')
reference_round=config.pop('referenceRound',None)
if reference_round is not None:
 if type(reference_round) is not int or reference_round < 1:
  raise ValueError('Invalid reference sample round')
 reference_file=root/'audit'/'power-ui-20261116'/f'private-originals-{reference_round}-by-file.json'
 previous=json.loads(reference_file.read_text(encoding='utf-8'))
 if previous['run'] != config.pop('referenceRun'):
  raise ValueError('The previous sample comes from a different reference run')
 labels=set(config['labels'])
 reference={row['label']:{key:row[key] for key in ('kind','bytes','seconds','container')}
            for row in previous['media'] if row['label'] in labels}
 if set(reference) != labels:
  raise ValueError('The previous run does not contain every selected source')
 config['referenceSources']=reference
if len(sys.argv) == 2:
 config['credentials']=json.loads(getpass.getpass('Private Emby input: '))
public_host = os.environ.get('YFUSE_QA_PUBLIC_HOST') or os.environ['YFUSE_QA_SSH_TARGET'].rsplit('@', 1)[-1]
config.update(keys=keys,host=public_host,port=19196)
def relay_code(name):
 if run != '44417724795':
  return (root/'qa-private-relay'/name).read_bytes()
 revision='f0efe80c70a1d9d0ba4e94a15731292f0f3deaf5'
 return subprocess.check_output(['git','-c','safe.directory='+root.as_posix(),'-C',str(root),'show',
                                 revision+':qa-private-relay/'+name])
config['relaySource']=relay_code('server.py').decode('utf-8')
resolver=(base/'source_bridge_entry.py').read_text(encoding='utf-8')
config['resolverSource']=resolver[:resolver.index("\nstage = 'config'")]
program=base64.b64encode(relay_code('serve_session.py')).decode()
command="exec(__import__('base64').b64decode("+repr(program)+"))"
process=subprocess.Popen(['ssh','-T','-o','BatchMode=yes','-o','ConnectTimeout=8','-o','ServerAliveInterval=30','-o','ServerAliveCountMax=4','--', os.environ['YFUSE_QA_SSH_TARGET'],'python3','-c',shlex.quote(command)],
                         stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,encoding='utf-8')
process.stdin.write(json.dumps(config)+'\n')
process.stdin.flush()
process.stdin.close()
config.clear()
receipt=base/('relay-session-'+run+('-attempt-'+attempt if attempt != '1' else '')+'.json')
started=False
for line in process.stdout:
 try:message=json.loads(line)
 except ValueError:continue
 if 'envelopes' in message:
  for shard,envelope in message['envelopes'].items():
   target=root/'qa-private-arm-input'/(run+'-'+shard+'-'+attempt+'.json')
   target.write_text(json.dumps(envelope),encoding='utf-8')
  summary=dict(message['summary'],run=run,attempt=attempt,startedUtc=datetime.datetime.now(datetime.timezone.utc).isoformat())
  receipt.write_text(json.dumps(summary,indent=2),encoding='utf-8')
  print(json.dumps({'started':True,'summary':summary}),flush=True)
  started=True
 elif message.get('stopped'):
  previous=json.loads(receipt.read_text(encoding='utf-8'))
  previous.update(stopped=True,bytesSent=message['bytesSent'],sourceRequests=message.get('sourceRequests'),stoppedUtc=datetime.datetime.now(datetime.timezone.utc).isoformat())
  if 'connections' in message:
   previous['connections']=message['connections']
  receipt.write_text(json.dumps(previous,indent=2),encoding='utf-8')
  print(json.dumps({'stopped':True,'bytesSent':message['bytesSent'],'sourceRequestCount':(message.get('sourceRequests') or {}).get('total')}),flush=True)
 elif 'errorType' in message:
  print(json.dumps({'errorType':message['errorType'],'stage':message.get('stage')}),flush=True)
code=process.wait()
print(json.dumps({'sshExit':code,'started':started}),flush=True)
raise SystemExit(code)
