from pathlib import Path
import hashlib, json, os, re, shutil, subprocess, zipfile

ROOT = Path(__file__).resolve().parent
OUT = ROOT / 'artifacts/apk'
OUT.mkdir(parents=True, exist_ok=True)
sdk = Path(os.environ.get('ANDROID_SDK_ROOT', os.environ.get('ANDROID_HOME', '')))
tool = sdk / 'build-tools/37.0.0'
manifest = json.loads((ROOT / 'app/src/main/assets/manifest.json').read_text())
metadata = []
for abi in ['arm64-v8a', 'x86']:
    apk = ROOT / f'app/build/outputs/apk/debug/app-{abi}-debug.apk'
    badging = subprocess.check_output([str(tool / 'aapt'), 'dump', 'badging', str(apk)], text=True)
    package = re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging, re.M)
    assert package and package.groups() == ('com.yfuse.offlineprobe.v2', '2', '0.0.2'), badging[:500]
    permissions = subprocess.check_output([str(tool / 'aapt'), 'dump', 'permissions', str(apk)], text=True)
    assert 'android.permission.INTERNET' not in permissions
    signature = subprocess.check_output([str(tool / 'apksigner'), 'verify', '--print-certs', str(apk)], text=True)
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        assert any(n.startswith('lib/' + abi + '/') and 'onnxruntime' in n for n in names)
        assert set(n for n in names if n.endswith('.onnx')) == {'assets/'+m['file'] for m in manifest['models']}
        assert 'classes.dex' in names
        for model in manifest['models']:
            assert hashlib.sha256(archive.read('assets/' + model['file'])).hexdigest() == model['sha256']
    name = f'Yfuse-offline-cutout-probe-0.0.2-{abi}.apk'
    target = OUT / name; shutil.copyfile(apk, target)
    metadata.append(dict(file=name, bytes=target.stat().st_size, sha256=hashlib.sha256(target.read_bytes()).hexdigest(),
                         version='0.0.2', code=2, package='com.yfuse.offlineprobe.v2', abi=abi, permissions=permissions, signature=signature))
(OUT / 'apk-metadata.json').write_text(json.dumps(metadata, indent=2))
shutil.copyfile(ROOT / 'app/src/main/assets/manifest.json', OUT / 'model-manifest.json')
print(json.dumps(metadata, indent=2))
