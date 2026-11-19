"""Cloud-only TLS-pinned bridge; Android sees the same bytes through an isolated loopback port."""
import base64
import json
import os
from pathlib import Path
import runpy
import secrets
import ssl
import subprocess
import sys
import threading
import urllib.request

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

sys.path.insert(0, str(Path(__file__).parent))
from server import BoundedHTTPServer, RelayState, handler_for
from admission import check_path

if os.environ.get("GITHUB_ACTIONS") != "true":
    raise SystemExit("Cloud runner required")

base = runpy.run_path("qa-private-arm/test.py")
items = base["load_envelope"]()
certificates = {row["relayCertificatePem"] for row in items.values()}
if len(certificates) != 1:
    raise ValueError("A single pinned test endpoint is required")
tls = ssl.create_default_context(cadata=certificates.pop())
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPSHandler(context=tls))
Path("private-results").mkdir(exist_ok=True)
admission = check_path(items, opener)
Path("private-results/remote-admission.json").write_text(json.dumps(admission, indent=2))
if not admission["remoteAdmissionPassed"]:
    Path("private-results/relay-path.json").write_text(json.dumps({
        "sourcePath": "temporary-server-relay", "remoteAdmissionPassed": False,
        "admissionEvidence": "remote-admission.json",
        "appPlaybackAttempts": 0,
    }))
    raise SystemExit("The temporary relay must be reachable before starting player tests")
token = secrets.token_urlsafe(32)
state = RelayState(items, token, 4 * 3600, 32 * 1024 ** 3, opener)
server = BoundedHTTPServer(("127.0.0.1", 18396), handler_for(state))
worker = threading.Thread(target=server.serve_forever, daemon=True)
worker.start()
try:
    subprocess.run(["adb", "reverse", "tcp:18396", "tcp:18396"], check=True)
    loopback_items = {label: dict(row, location="http://127.0.0.1:18396/" + token + "/" + label)
                      for label, row in items.items()}
    for row in loopback_items.values():
        row.pop("relayCertificatePem")
    # Keep the on-disk session encrypted even though the replacements only address loopback.
    temp = Path(os.environ["RUNNER_TEMP"])
    private = serialization.load_pem_private_key((temp / "qa-private.pem").read_bytes(), password=None)
    key, nonce = os.urandom(32), os.urandom(12)
    ciphertext = AESGCM(key).encrypt(nonce, json.dumps(loopback_items).encode(),
                                     (os.environ["GITHUB_RUN_ID"] + "-" + os.environ["QA_SHARD"]).encode())
    wrapped = private.public_key().encrypt(key, padding.OAEP(mgf=padding.MGF1(hashes.SHA256()),
                                                           algorithm=hashes.SHA256(), label=None))
    envelope = {k: base64.b64encode(v).decode() for k, v in
                (("key", wrapped), ("nonce", nonce), ("ciphertext", ciphertext))}
    (temp / "envelope.json").write_text(json.dumps(envelope))
    os.environ['QA_VERIFIED_PRIVATE_RELAY'] = 'true'
    runpy.run_path("qa-private-arm/gate.py", run_name="__main__")
finally:
    state.stopped.set()
    server.shutdown()
    server.server_close()
    worker.join(2)
    Path("private-results").mkdir(exist_ok=True)
    Path("private-results/relay-path.json").write_text(json.dumps({
        "sourcePath": "temporary-server-relay", "tlsCertificatePinned": True,
        "loopbackOnly": True, "bytesForwarded": state.bytes_sent, "admissionEvidence": "remote-admission.json",
        "requests": state.request_summary(), "connections": server.connection_summary(),
        "note": "This validates original media bytes via the source server; it does not prove direct cloud-to-CDN access.",
    }))
