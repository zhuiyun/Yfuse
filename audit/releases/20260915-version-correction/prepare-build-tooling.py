from pathlib import Path

AUDIT = Path(__file__).resolve().parent
ROOT = AUDIT.parents[2]
OLD = ROOT / 'audit/releases/20260915-ambient-family'
OUT = ROOT / 'artifacts/releases/ambient-family-1.0.65-227'
OUT.mkdir(parents=True, exist_ok=True)
for name in ('version.properties', 'release-notes.txt'):
    (OUT / name).write_bytes((ROOT / name).read_bytes())
build = (OLD / 'build-release.ps1').read_text(encoding='utf-8')
build = build.replace('ambient-family-1.0.56-218/Yfuse-1.0.56-218', 'ambient-family-1.0.65-227/Yfuse-1.0.65-227')
(AUDIT / 'build-release.ps1').write_text(build, encoding='utf-8')
manifest = (OLD / 'source-manifest.py').read_text(encoding='utf-8')
manifest = manifest.replace("'composeApp/src', 'mdkAndroid/src', 'watchTogetherProtocol/src', 'gradle', 'composeApp/libs', 'mdkAndroid/libs'",
    "'composeApp/src', 'mdkAndroid/src', 'watchTogetherProtocol/src', 'watchTogetherServer/src', 'tvApp/src', 'gradle', 'composeApp/libs', 'mdkAndroid/libs', 'native', 'scripts'")
manifest = manifest.replace("if p.is_file())", "if p.is_file() and '__pycache__' not in p.parts)")
(AUDIT / 'source-manifest.py').write_text(manifest, encoding='utf-8')
verify = (OLD / 'verify-release.py').read_text(encoding='utf-8')
verify = verify.replace('ambient-family-1.0.56-218', 'ambient-family-1.0.65-227').replace('Yfuse-1.0.56-218-', 'Yfuse-1.0.65-227-')
verify = verify.replace("ROOT / 'artifacts/releases/search-field-1.0.55-217/Yfuse-1.0.55-217-search-field-full-arm64-signed.apk'",
                        "AUDIT / 'previous-1.0.64-226/Yfuse-226-1.0.64-signed.apk'")
verify = verify.replace("== '1.0.56'", "== '1.0.65'").replace("== '218'", "== '227'")
verify = verify.replace("'tests': {'composeApp': 2576, 'tvApp': 63, 'failures': 0, 'newAmbientRegressions': 8},",
                        "'tests': json.loads((AUDIT / 'test-summary.json').read_text(encoding='utf-8')),")
(AUDIT / 'verify-release.py').write_text(verify, encoding='utf-8')
regression = (ROOT / 'audit/product-implementation-20260915/run-verification.ps1').read_text(encoding='utf-8')
regression = regression.replace('audit/product-implementation-20260915/verification.log',
                                'audit/releases/20260915-version-correction/verification.log')
(AUDIT / 'run-verification.ps1').write_text(regression, encoding='utf-8')
print('Prepared 1.0.65 build and verification scripts.')
