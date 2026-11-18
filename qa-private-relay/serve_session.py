"""Explicitly started temporary server session. Configuration arrives over SSH stdin."""
import base64
import concurrent.futures
import datetime
import http.client
import ipaddress
import json
import os
from pathlib import Path
import secrets
import signal
import ssl
import sys
import tempfile
import urllib.parse
from collections import Counter

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.x509.oid import ExtendedKeyUsageOID, NameOID

STAGE = "configuration"


def temporary_certificate(host, lifetime):
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    subject = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "Yfuse temporary cloud test")])
    now = datetime.datetime.now(datetime.timezone.utc)
    certificate = (x509.CertificateBuilder().subject_name(subject).issuer_name(subject)
                   .public_key(key.public_key()).serial_number(x509.random_serial_number())
                   .not_valid_before(now - datetime.timedelta(minutes=2))
                   .not_valid_after(now + datetime.timedelta(seconds=lifetime + 300))
                   .add_extension(x509.SubjectAlternativeName([x509.IPAddress(ipaddress.ip_address(host))]), False)
                   .add_extension(x509.BasicConstraints(ca=True, path_length=0), True)
                   .add_extension(x509.KeyUsage(True, False, True, False, False, True, True, False, False), True)
                   .add_extension(x509.ExtendedKeyUsage([ExtendedKeyUsageOID.SERVER_AUTH]), False)
                   .add_extension(x509.SubjectKeyIdentifier.from_public_key(key.public_key()), False)
                   .add_extension(x509.AuthorityKeyIdentifier.from_issuer_public_key(key.public_key()), False)
                   .sign(key, hashes.SHA256()))
    return key, certificate.public_bytes(serialization.Encoding.PEM).decode()


def catalog_identity(item):
    source = (item.get("MediaSources") or [{}])[0]
    ticks = source.get("RunTimeTicks") or item.get("RunTimeTicks") or 0
    return int(source.get("Size") or 0), int(ticks) // 10_000_000, source.get("Container") or "mkv"


def select_sources(resolver, labels, reference):
    selected = {}
    if reference is not None and set(reference) != labels:
        raise ValueError("The reference must identify every requested source")
    for kind, prefix in (("Movie", "m-"), ("Episode", "e-")):
        catalog = resolver["candidates"](kind)
        if reference is None:
            for index, item in enumerate(resolver["choose"](catalog, 20)):
                label = prefix + str(index).zfill(3)
                if label in labels:
                    selected[label] = item
            continue
        by_identity = {}
        for item in catalog:
            by_identity.setdefault(catalog_identity(item), []).append(item)
        for label in sorted(labels):
            if not label.startswith(prefix):
                continue
            entry = reference[label]
            matches = by_identity.get((entry["bytes"], entry["seconds"], entry["container"]), [])
            if entry["kind"] != kind or len(matches) != 1:
                raise ValueError("A reference source is absent or ambiguous in the current catalog")
            selected[label] = matches[0]
    if set(selected) != labels or len({item["Id"] for item in selected.values()}) != len(labels):
        raise ValueError("The requested source selection changed")
    return list(selected.items())


def close_emby_session(resolver):
    root = urllib.parse.urlsplit(resolver["ROOT"])
    if root.scheme != "http" or root.hostname != "127.0.0.1":
        raise ValueError("Expected the server-local Emby API")
    api = http.client.HTTPConnection(root.hostname, root.port, timeout=15)
    try:
        api.request("POST", root.path.rstrip("/") + "/Sessions/Logout", body=b"{}", headers={
            "X-Emby-Authorization": resolver["AUTH"], "X-Emby-Token": resolver["TOKEN"],
            "Content-Type": "application/json",
        })
        if api.getresponse().status not in (200, 204):
            raise ValueError("The source session did not log out")
    finally:
        api.close()


def run(config):
    global STAGE
    # Only the reviewed local sources are supplied by the SSH launcher, never by a cloud client.
    relay = {}
    resolver = {}
    exec(config.pop("relaySource"), relay)
    exec(config.pop("resolverSource"), resolver)
    host = str(ipaddress.ip_address(config["host"]))
    if not ipaddress.ip_address(host).is_global or config["port"] != 19196:
        raise ValueError("Unexpected test endpoint")
    runner_ips = config.get("runnerIps")
    allowed_clients = None
    if runner_ips is not None:
        if set(runner_ips) != set(config["keys"]):
            raise ValueError("Missing address for an authorized runner")
        addresses = [ipaddress.IPv4Address(value) for value in runner_ips.values()]
        if not all(address.is_global for address in addresses):
            raise ValueError("A public runner address is required")
        allowed_clients = {str(address) for address in addresses}
    wanted = set(config["labels"])
    if not wanted:
        raise ValueError("An explicit media allowlist is required")
    try:
        STAGE = "server-login"
        login = resolver["call"]("/Users/AuthenticateByName", {
            "Username": config["credentials"][0], "Pw": config["credentials"][1],
        })
        resolver["TOKEN"] = login["AccessToken"]
        resolver["USER"] = login["User"]["Id"]
        STAGE = "server-inventory"
        reference = config.get("referenceSources")
        selected = select_sources(resolver, wanted, reference)
        media = {}
        STAGE = "source-resolution"
        with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
            for label, row, error in pool.map(resolver["resolve"], selected):
                if error or not row:
                    raise ValueError("A selected source could not be resolved")
                if set(row["rangeHashes"]) != {"start", "middle", "end"}:
                    raise ValueError("The source did not supply all three requested byte ranges")
                if reference is not None:
                    expected = reference[label]
                    if (row["type"], row["size"], row["seconds"], row["container"]) != (
                            expected["kind"], expected["bytes"], expected["seconds"], expected["container"]):
                        raise ValueError("The resolved playback file differs from its catalog reference")
                media[label] = row
    finally:
        if resolver.get("TOKEN"):
            try:
                STAGE = "server-logout"
                close_emby_session(resolver)
            finally:
                resolver["TOKEN"] = None
        config.pop("credentials", None)
    capability = secrets.token_urlsafe(32)
    STAGE = "temporary-tls"
    state = relay["RelayState"](media, capability, config["lifetimeSeconds"], config["byteBudget"])
    key, certificate_pem = temporary_certificate(host, config["lifetimeSeconds"])
    for sig in (signal.SIGTERM, signal.SIGINT, signal.SIGHUP):
        signal.signal(sig, lambda *_: state.stopped.set())
    os.umask(0o077)
    with tempfile.TemporaryDirectory(prefix="yfuse-cloud-relay-") as directory:
        cert_file = Path(directory) / "certificate.pem"
        key_file = Path(directory) / "key.pem"
        cert_file.write_text(certificate_pem)
        key_file.write_bytes(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                                               serialization.NoEncryption()))
        tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        tls.options |= ssl.OP_NO_TLSv1 | ssl.OP_NO_TLSv1_1
        tls.load_cert_chain(str(cert_file), str(key_file))
        STAGE = "temporary-listener"
        server = relay["BoundedHTTPServer"](("0.0.0.0", config["port"]), relay["handler_for"](state),
                                           tls, allowed_clients=allowed_clients)
        server.timeout = 1
        try:
            STAGE = "encrypted-manifest"
            envelopes = {}
            for shard, public in config["keys"].items():
                subset = {}
                for index, (label, _) in enumerate(selected):
                    if index % len(config["keys"]) != int(shard):
                        continue
                    row = {k: v for k, v in media[label].items() if k not in ("location", "providerEntry")}
                    row["location"] = "https://%s:%d/%s/%s" % (host, config["port"], capability, label)
                    row["relayCertificatePem"] = certificate_pem
                    subset[label] = row
                wrapping_key = serialization.load_pem_public_key(public.encode())
                aes_key, nonce = os.urandom(32), os.urandom(12)
                encrypted = AESGCM(aes_key).encrypt(nonce, json.dumps(subset).encode(),
                                                   (config["run"] + "-" + shard).encode())
                wrapped = wrapping_key.encrypt(aes_key, padding.OAEP(mgf=padding.MGF1(hashes.SHA256()),
                                                                     algorithm=hashes.SHA256(), label=None))
                envelopes[shard] = {k: base64.b64encode(v).decode() for k, v in
                                    (("key", wrapped), ("nonce", nonce), ("ciphertext", encrypted))}
            summary = {"pid": os.getpid(), "processStartTicks": Path("/proc/self/stat").read_text().split()[21],
                       "port": config["port"], "selected": len(media), "lifetimeSeconds": config["lifetimeSeconds"],
                       "byteBudget": config["byteBudget"],
                       "sourceHTTP": dict(Counter(str(code) for row in media.values() for code in row["rangeCodes"].values()))}
            print(json.dumps({"envelopes": envelopes, "summary": summary}), flush=True)
            STAGE = "serving"
            while state.alive():
                server.handle_request()
        finally:
            state.stopped.set()
            server.server_close()
    print(json.dumps({"stopped": True, "bytesSent": state.bytes_sent,
                      "sourceRequests": state.request_summary()}), flush=True)


if __name__ == "__main__":
    try:
        run(json.loads(sys.stdin.readline()))
    except Exception as error:
        print(json.dumps({"errorType": type(error).__name__, "stage": STAGE}), flush=True)
        raise SystemExit(1)
