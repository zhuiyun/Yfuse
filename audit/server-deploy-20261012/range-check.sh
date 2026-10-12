#!/usr/bin/env bash
set -euo pipefail
python3 - <<'PY'
import time,urllib.request
url='https://mcr.microsoft.com/v2/playwright/blobs/sha256:0926a8eb0e608a5c6888d1cd5594184bdf3ed3aa311dba5b42a547caefdc6f2e'
started=time.monotonic()
request=urllib.request.Request(url,headers={'Range':'bytes=0-131071'})
with urllib.request.urlopen(request,timeout=25) as response:
    assert response.status==206,response.status
    data=response.read(131072)
    assert len(data)==131072
print('official_blob_range_bytes='+str(len(data)))
print('official_blob_range_seconds='+str(round(time.monotonic()-started,2)))
PY
