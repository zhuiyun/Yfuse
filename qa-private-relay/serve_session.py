"""Explicitly started temporary server session. Configuration arrives over SSH stdin."""
import base64
import concurrent.futures
import datetime
import ipaddress
import json
import os
from pathlib import Path
import secrets
import signal
import ssl
import sys
import tempfile
from collections import Counter

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.x509.oid import ExtendedKeyUsageOID, NameOID


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


def run(config):
    # Only the reviewed local sources are supplied by the SSH launcher, never by a cloud client.
    relay = {}
    resolver = {}
    exec(config.pop("relaySource"), relay)
    exec(config.pop("resolverSource"), resolver)
    host = str(ipaddress.ip_address(config["host"]))
    if not ipaddress.ip_address(host).is_global or config["port"] != 19196:
        raise ValueError("Unexpected test endpoint")
    wanted = set(config["labels"])
    if not wanted:
        raise ValueError("An explicit media allowlist is required")
    try:
        login = resolver["call"]("/Users/AuthenticateByName", {
            "Username": config["credentials"][0], "Pw": config["credentials"][1],
        })
        resolver["TOKEN"] = login["AccessToken"]
        resolver["USER"] = login["User"]["Id"]
        selected = []
        for kind in ("Movie", "Episode"):
            rows = resolver["choose"](resolver["candidates"](kind), 20)
            selected.extend((("m-" if kind == "Movie" else "e-") + str(index).zfill(3), item)
                            for index, item in enumerate(rows))
        selected = [(label, item) for label, item in selected if label in wanted]
        if {label for label, _ in selected} != wanted:
            raise ValueError("The requested source selection changed")
        media = {}
        with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
            for label, row, error in pool.map(resolver["resolve"], selected):
                if error or not row:
                    raise ValueError("A selected source could not be resolved")
                media[label] = row
    finally:
        if resolver.get("TOKEN"):
            try:
                resolver["call"]("/Sessions/Logout", {})
            finally:
                resolver["TOKEN"] = None
        config.pop("credentials", None)
    capability = secrets.token_urlsafe(32)
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
        server = relay["BoundedHTTPServer"](("0.0.0.0", config["port"]), relay["handler_for"](state), tls)
        server.timeout = 1
        try:
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
            while state.alive():
                server.handle_request()
        finally:
            state.stopped.set()
            server.server_close()
    print(json.dumps({"stopped": True, "bytesSent": state.bytes_sent}), flush=True)


if __name__ == "__main__":
    try:
        run(json.loads(sys.stdin.readline()))
    except Exception as error:
        print(json.dumps({"errorType": type(error).__name__}), flush=True)
        raise SystemExit(1)
