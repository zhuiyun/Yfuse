import base64, io, json, os, sys, time, urllib.request, zipfile
from pathlib import Path
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding,rsa
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
temp=Path(os.environ['RUNNER_TEMP']); run=os.environ['GITHUB_RUN_ID'];priv=temp/'real-private.pem';cfg=temp/'real-config.json'
if sys.argv[1]=='key':
    key=rsa.generate_private_key(public_exponent=65537,key_size=3072)
    priv.write_bytes(key.private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.PKCS8,serialization.NoEncryption()));priv.chmod(0o600)
    Path('public').mkdir(exist_ok=True);Path('public/receiver.pem').write_bytes(key.public_key().public_bytes(serialization.Encoding.PEM,serialization.PublicFormat.SubjectPublicKeyInfo))
elif sys.argv[1]=='receive':
    base='https://api.github.com/repos/'+os.environ['GITHUB_REPOSITORY']
    def get(path):
        req=urllib.request.Request(base+path,headers={'Authorization':'Bearer '+os.environ['READ_TOKEN'],'User-Agent':'yfuse-design-receiver'})
        with urllib.request.urlopen(req,timeout=10) as r:return json.load(r)
    for tick in range(180):
        try:
            itm=get('/contents/audit/design358/transfer-'+run+'.txt?ref=audit/build-358-visual-review')
            sha=base64.b64decode(itm['content']).decode().strip()
            if not (len(sha)==40 and sha.isalnum()):raise ValueError()
            blob=base64.b64decode(get('/git/blobs/'+sha)['content'])
            key=serialization.load_pem_private_key(priv.read_bytes(),password=None)
            payload=key.decrypt(blob,padding.OAEP(mgf=padding.MGF1(hashes.SHA256()),algorithm=hashes.SHA256(),label=run.encode()))
            cfg.write_bytes(payload);cfg.chmod(0o600);priv.unlink();print('One-use input received.');break
        except Exception:time.sleep(5)
    else:raise SystemExit('No sealed input received')
elif sys.argv[1]=='seal':
    if not cfg.exists():raise SystemExit('No private result')
    key=base64.b64decode(json.loads(cfg.read_text())['key'])
    raw=io.BytesIO()
    with zipfile.ZipFile(raw,'w',zipfile.ZIP_DEFLATED) as z:
        for f in sorted((temp/'private-review').glob('*')):
            if f.is_file():z.write(f,f.name)
    nonce=os.urandom(12);Path('sealed').mkdir(exist_ok=True);Path('sealed/review.bin').write_bytes(nonce+AESGCM(key).encrypt(nonce,raw.getvalue(),run.encode()));cfg.unlink(missing_ok=True)
