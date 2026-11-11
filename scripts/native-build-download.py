#!/usr/bin/env python3
"""Restricted wget replacement for the pinned native builder, not a general downloader.

The wrapper's historical Lua HTTP URL is upgraded before any request. The floating
gas-preprocessor/master input is replaced with a reviewed commit and SHA-256.
Other supported upstream archives retain their versioned HTTPS URLs; this does
not claim that every upstream Git tag is an immutable source lock.
"""
import hashlib
import http.client
import json
import os
import shutil
import ssl
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

GAS_COMMIT = "ac1836309c2e77023c228b7184485597286289d3"
GAS_SHA256 = "7124d70cdecba7c5612f9a71fbf3f28514dd9c2ca3022f58ad793f88bb925fcf"
LUA_SHA256 = "b9e2e4aad6789b3b63a056d442f7b39f0ecfca3ae0f1fc0ae4e9614401b69f4b"
# Lua checksum: https://www.lua.org/ftp/ (lua-5.2.4.tar.gz).
# Gas checksum: UTF-8 bytes of gas-preprocessor.pl at the full commit above.
SOURCES = {
    "http://www.lua.org/ftp/lua-5.2.4.tar.gz":
        ("https://www.lua.org/ftp/lua-5.2.4.tar.gz", LUA_SHA256),
    "https://www.lua.org/ftp/lua-5.2.4.tar.gz":
        ("https://www.lua.org/ftp/lua-5.2.4.tar.gz", LUA_SHA256),
    "https://github.com/FFmpeg/gas-preprocessor/raw/master/gas-preprocessor.pl":
        (f"https://raw.githubusercontent.com/FFmpeg/gas-preprocessor/{GAS_COMMIT}/gas-preprocessor.pl", GAS_SHA256),
    "https://github.com/adah1972/libunibreak/releases/download/libunibreak_6_1/libunibreak-6.1.tar.gz":
        ("https://github.com/adah1972/libunibreak/releases/download/libunibreak_6_1/libunibreak-6.1.tar.gz", None),
    "https://dl.google.com/android/repository/commandlinetools-linux-14742923_latest.zip":
        ("https://dl.google.com/android/repository/commandlinetools-linux-14742923_latest.zip", None),
}
MAX_BYTES = 256 * 1024 * 1024
# Three total attempts, with at most three seconds of backoff. Never retry checks
# on source identity, transport policy, checksums, or local output/receipt writes.
RETRY_DELAYS = (1, 2)


class HttpsRedirects(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, new_url):
        if urllib.parse.urlparse(new_url).scheme != "https":
            raise ValueError("Native input redirect must remain HTTPS")
        return super().redirect_request(request, fp, code, message, headers, new_url)


def source_for(url):
    if url not in SOURCES:
        raise ValueError("Unreviewed native archive URL; update the explicit source allowlist")
    return SOURCES[url]


def transient_network_error(error):
    if isinstance(error, urllib.error.HTTPError):
        return error.code in (429, 500, 502, 503, 504)
    if isinstance(error, urllib.error.URLError):
        return transient_network_error(error.reason)
    # In particular, do not retry certificate verification or other TLS errors.
    return isinstance(error, (TimeoutError, ConnectionError, ssl.SSLEOFError,
                              http.client.IncompleteRead))


def download(url, output, opener=None):
    resolved, expected = source_for(url)
    opener = opener or urllib.request.build_opener(HttpsRedirects())
    # Never pipe unverified bytes into tar or expose a partial executable.
    with tempfile.TemporaryFile() as staged:
        for attempt in range(len(RETRY_DELAYS) + 1):
            # A retry is a full new transfer of exactly the same resolved input.
            staged.seek(0)
            staged.truncate(0)
            digest = hashlib.sha256()
            total = 0
            try:
                with opener.open(resolved, timeout=90) as response:
                    if urllib.parse.urlparse(response.geturl()).scheme != "https":
                        raise ValueError("Native input response must use HTTPS")
                    length = response.headers.get("Content-Length")
                    length = int(length) if length is not None else None
                    if length is not None and length < 0:
                        raise ValueError("Invalid native input Content-Length")
                    if length is not None and length > MAX_BYTES:
                        raise ValueError("Native input exceeds download budget")
                    while chunk := response.read(128 * 1024):
                        total += len(chunk)
                        if total > MAX_BYTES:
                            raise ValueError("Native input exceeds download budget")
                        digest.update(chunk)
                        staged.write(chunk)
                    # HTTPResponse.read(size) can silently return early EOF, even
                    # with Content-Length. Do not accept truncated unpinned inputs.
                    if length is not None and total < length:
                        raise http.client.IncompleteRead(b"", length - total)
                    if length is not None and total > length:
                        raise ValueError("Native input exceeds Content-Length")
            except (OSError, http.client.HTTPException) as error:
                if not transient_network_error(error) or attempt == len(RETRY_DELAYS):
                    raise
                if isinstance(error, urllib.error.HTTPError):
                    error.close()
                delay = RETRY_DELAYS[attempt]
                print(f"[native-input] transient download failure: {error}; "
                      f"retry {attempt + 2}/{len(RETRY_DELAYS) + 1} in {delay}s", file=sys.stderr)
                time.sleep(delay)
            else:
                break
        if total == 0 or (expected is not None and digest.hexdigest() != expected):
            raise ValueError("Native input checksum mismatch or empty download")
        ledger = os.environ.get("YCORE_DOWNLOAD_RECEIPTS")
        if ledger:
            path = Path(ledger)
            path.parent.mkdir(parents=True, exist_ok=True)
            with path.open("a", encoding="utf-8") as record:
                record.write(json.dumps({"url": resolved, "sha256": digest.hexdigest(),
                                         "bytes": total, "checksumPinned": expected is not None},
                                        sort_keys=True) + "\n")
        staged.seek(0)
        if output == "-":
            shutil.copyfileobj(staged, sys.stdout.buffer)
        else:
            with Path(output).open("wb") as destination:
                shutil.copyfileobj(staged, destination)
        print(f"[native-input] {Path(urllib.parse.urlparse(resolved).path).name}: "
              f"sha256={digest.hexdigest()} bytes={total} "
              f"checksumPinned={expected is not None}", file=sys.stderr)


def main(argv=None):
    args = list(sys.argv[1:] if argv is None else argv)
    try:
        if len(args) == 1:
            url = args[0]
            output = Path(urllib.parse.urlparse(url).path).name
        elif len(args) == 3 and args[1] == "-O":
            url, _, output = args
        else:
            raise ValueError("Only URL or URL -O FILE is supported")
        download(url, output)
        return 0
    except (ValueError, OSError, http.client.HTTPException) as error:
        print(f"Native input download failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
