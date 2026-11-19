"""Check the pinned path before using the disposable Android installation."""
import hashlib
import http.client
import ssl
import time
import urllib.error
import urllib.request

from server import request_error_fields


def check_path(items, opener):
    # Use fresh connections to a movie and an episode. One successful first byte
    # concealed an intermittently failing cloud route during the last campaign.
    samples = []
    seen_kinds = set()
    representatives = []
    for label, media in items.items():
        kind = label.split('-', 1)[0]
        if kind not in seen_kinds:
            representatives.append((label, media))
            seen_kinds.add(kind)
    for repeat in range(2):
        for label, media in representatives:
            size = media['size']
            for name, offset in (('start', 0), ('middle', size // 2), ('end', size - 1024)):
                hi = offset + 1023
                row = {'round': repeat + 1, 'label': label, 'region': name, 'matched': False}
                request = urllib.request.Request(media['location'], headers={
                    'Range': f'bytes={offset}-{hi}', 'User-Agent': 'Emby for Android Mobile',
                    'Accept-Encoding': 'identity',
                })
                began = time.monotonic()
                try:
                    with opener.open(request, timeout=12) as response:
                        row['httpStatus'] = response.status
                        content = response.read(1025)
                        row['matched'] = (response.status == 206 and len(content) == 1024 and
                                          response.headers.get('Content-Range') == f'bytes {offset}-{hi}/{size}' and
                                          hashlib.sha256(content).hexdigest()[:16] == media['rangeHashes'][name])
                        if not row['matched']:
                            row['stopReason'] = 'unexpected-response-bytes-or-range'
                except (OSError, EOFError, http.client.HTTPException) as error:
                    row.update(request_error_fields(error))
                    if isinstance(error, urllib.error.HTTPError):
                        row['httpStatus'] = error.code
                        error.close()
                    if (row.get('httpStatus') in (401, 403, 404, 410, 412, 429) or
                            'tlsVerifyCode' in row or isinstance(error, ssl.SSLCertVerificationError)):
                        row['stopReason'] = 'endpoint-refused-or-certificate-invalid'
                row['elapsedMs'] = round(1000 * (time.monotonic() - began))
                samples.append(row)
                if 'stopReason' in row:
                    return {'remoteAdmissionPassed': False, 'allSamplesMatched': False, 'samples': samples}
        if not any(row['matched'] for row in samples):
            break
    return {'remoteAdmissionPassed': any(row['matched'] for row in samples),
            'allSamplesMatched': bool(samples) and all(row['matched'] for row in samples), 'samples': samples}


def measure_ranges(media, opener):
    # Bounded transport measurements only; no bytes, titles, URLs or response headers leave the runner.
    size = int(media.get("size", 0))
    amount = 8 * 1024 * 1024
    if size < amount:
        return []
    measurements = []
    for position in (0, (size - amount) // 2, size - amount):
        end = position + amount - 1
        request = urllib.request.Request(media["location"], headers={
            "Range": f"bytes={position}-{end}", "User-Agent": "Emby for Android Mobile", "Accept-Encoding": "identity",
        })
        begin = time.monotonic()
        row = {"start": position, "end": end, "status": 0, "bytes": 0, "headersMs": None, "firstByteMs": None}
        try:
            with opener.open(request, timeout=15) as response:
                row["status"] = response.status
                row["headersMs"] = round((time.monotonic() - begin) * 1000)
                if response.status != 206 or response.headers.get("Content-Range") != f"bytes {position}-{end}/{size}":
                    raise ValueError("Range benchmark requires the exact partial response")
                if response.read(1):
                    row["bytes"] = 1
                    row["firstByteMs"] = round((time.monotonic() - begin) * 1000)
                while 0 < row["bytes"] < amount and time.monotonic() - begin < 25:
                    data = response.read(min(262144, amount - row["bytes"]))
                    if not data:
                        break
                    row["bytes"] += len(data)
        except (OSError, EOFError, ValueError, http.client.HTTPException) as failure:
            row.update(request_error_fields(failure))
            if isinstance(failure, urllib.error.HTTPError):
                row["status"] = failure.code
                failure.close()
            if isinstance(failure, ssl.SSLCertVerificationError):
                row["tlsVerifyCode"] = failure.verify_code
        elapsed = time.monotonic() - begin
        row.update(elapsedMs=round(elapsed * 1000), bitsPerSecond=round(row["bytes"] * 8 / max(elapsed, .001)),
                   complete=row["bytes"] == amount and "errorType" not in row)
        measurements.append(row)
        if row["status"] in (401, 403, 404, 410, 412, 429) or "tlsVerifyCode" in row or row.get("errorType") == "ValueError":
            break
    return measurements
