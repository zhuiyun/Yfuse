# Repository validation environment

Use Python 3.10+; validation CI selects 3.13. Checks do not build or publish an APK.
Python dependencies are pinned in `scripts/requirements-validation.txt` and should be
installed in a workspace virtual environment, not into the global interpreter.

## Profiles

| Profile | Required tools | What a successful precheck means |
| --- | --- | --- |
| `scripts` | Python, bash, OpenSSL, jq | Release-script tests can start |
| `harmony-source` | Python, PyYAML 6.0.3, host g++/clang++, nm | Contract and portable ABI checks can start |
| `harmony-host` | Python, host cjc, configured matching stdx import directory | Host compilation can start; cjc still verifies actual packages |
| `android` | Java, configured Android SDK 37/37.0, Node | Basic project tools are present; no build or device validation is implied |

The portable native ABI verifier currently uses ELF shared libraries and `nm -D`.
Run that part on Linux/CI. Windows can run release-script tests and standalone
Cangjie host checks; absence of a compatible native check environment is BLOCKED.
The Android profile checks `ANDROID_HOME`/`ANDROID_SDK_ROOT` explicitly; Gradle may
also use local SDK configuration. Never copy credentials from local configuration
into diagnostics. Android CI uses JDK 21 and the fast server/protocol job uses 17.

## Linux / CI

Provision bash, OpenSSL, jq, a host C++ compiler and binutils (`nm`), then run:

```bash
python3 -m venv build/validation-env
build/validation-env/bin/python -m pip install -r scripts/requirements-validation.txt
build/validation-env/bin/python scripts/check-validation-env.py --profile scripts harmony-source --report build/validation/env.json
build/validation-env/bin/python -m unittest discover -s scripts/tests -p 'test_*.py'
build/validation-env/bin/python scripts/verify-harmony-port.py --report build/validation/harmony-source.json
```

## Windows / PowerShell

Run from the repository root. Git for Windows supplies bash and OpenSSL; add its
bin directories only to the current process PATH. Keep downloaded tools under
`build/validation-tools` (ignored by Git).

```powershell
python -m venv build/validation-env
& .\build\validation-env\Scripts\python.exe -m pip install -r scripts/requirements-validation.txt
New-Item -ItemType Directory -Path build/validation-tools -Force | Out-Null
Invoke-WebRequest 'https://github.com/jqlang/jq/releases/download/jq-1.8.1/jq-windows-amd64.exe' -OutFile build/validation-tools/jq.exe
$jqHash = (Get-FileHash -LiteralPath build/validation-tools/jq.exe -Algorithm SHA256).Hash.ToLowerInvariant()
if ($jqHash -ne '23cb60a1354eed6bcc8d9b9735e8c7b388cd1fdcb75726b93bc299ef22dd9334') { throw 'jq checksum mismatch' }
$env:PATH = "$PWD\build\validation-tools;C:\Program Files\Git\bin;C:\Program Files\Git\usr\bin;$env:PATH"
& .\build\validation-env\Scripts\python.exe scripts/check-validation-env.py --profile scripts --report build/validation/scripts-env.json
& .\build\validation-env\Scripts\python.exe -m unittest discover -s scripts/tests -p 'test_*.py'
```

The jq checksum above matches the official jq 1.8.1 Windows amd64 release asset.
Do not add a downloaded executable to a permanent/global PATH as part of testing.

## Source coverage versus complete host coverage

`verify-cangjie-host.py` stages platform-independent sources. It excludes ArkUI and
Harmony ability entry points and removes the platform HTTP adapter for the host
build. Its results never validate a HAP or device behavior.

```bash
python scripts/verify-cangjie-host.py --report build/validation/cangjie-host.json
python scripts/verify-cangjie-host.py --require-complete --report build/validation/cangjie-host-strict.json
python scripts/verify-harmony-port.py --require-host --report build/validation/harmony-strict.json
```

Set `CANGJIE_HOME` to the host compiler installation and `CANGJIE_STDX_PATH` to the
matching host stdx import directory (`stdx.encoding.json` and `stdx.encoding.url`).
For a Harmony release, use the actual DevEco/Cangjie SDK, signed HAP build and
runtime evidence required by `harmonyApp/RELEASE_CHECKLIST.md`; host completeness
is only one prerequisite.

| Result | Console / JSON | Exit code |
| --- | --- | --- |
| Complete host or all checks passed | PASS / `passed` | 0 |
| Optional host missing | SKIP / `skipped`; aggregate `partial` | 0; strict mode 2 |
| Optional host dependency coverage incomplete | PARTIAL / `partial` | 0; strict mode 2 |
| Required source/environment dependency missing | BLOCKED / `blocked` | 2 |
| Compiler, test or contract failed | FAIL / `failed` | 1 |

In source-only CI, exit 0 permits explicitly partial host coverage because no
Cangjie SDK is provisioned. Read and archive the JSON coverage; never translate
that exit code into a claim that host, platform or release validation completed.
The Harmony workflow listens on master and the retained feature branch, plus PRs,
and saves actual check coverage even when another step fails.