"""An Emby-only read and logout using stdin over the server's existing SSH transport."""
import os
import json
from pathlib import Path
import shlex
import subprocess
import sys
from datetime import datetime, timezone

REMOTE = "import http.client\nimport json\nimport sys\nfrom urllib.parse import quote, urlencode\n\ncredentials = json.loads(sys.stdin.readline())\nif set(credentials) != {\"username\", \"password\"}:\n    raise SystemExit(\"Unexpected Emby input fields\")\nauthorization = 'MediaBrowser Client=\"Yfuse QA\", Device=\"Cloud playback preflight\", DeviceId=\"yfuse-emby-check-44417724795\", Version=\"1.9.6\"'\nheaders = {\"X-Emby-Authorization\": authorization, \"Content-Type\": \"application/json\"}\ndef call(method, path, body=None):\n    connection = http.client.HTTPConnection(\"127.0.0.1\", 19001, timeout=20)\n    try:\n        data = json.dumps(body).encode() if body is not None else None\n        connection.request(method, \"/emby\" + path, body=data, headers=headers)\n        response = connection.getresponse()\n        return response.status, response.read(2 * 1024 * 1024)\n    finally:\n        connection.close()\n\nstatus, raw = call(\"POST\", \"/Users/AuthenticateByName\", {\n    \"Username\": credentials.pop(\"username\"), \"Pw\": credentials.pop(\"password\")})\nif status != 200:\n    print(json.dumps({\"authenticated\": False, \"httpStatus\": status}))\n    raise SystemExit(1)\nlogin = json.loads(raw)\nheaders[\"X-Emby-Token\"] = login[\"AccessToken\"]\nuser = quote(login[\"User\"][\"Id\"], safe=\"\")\ndel raw, login\nreport = {\"authenticated\": True, \"readableTypes\": {}, \"loggedOut\": False}\ntry:\n    for kind in (\"Movie\", \"Episode\"):\n        query = urlencode({\"Recursive\": \"true\", \"IncludeItemTypes\": kind, \"Limit\": 1,\n                           \"EnableImages\": \"false\", \"EnableUserData\": \"false\"})\n        status, raw = call(\"GET\", \"/Users/\" + user + \"/Items?\" + query)\n        if status != 200:\n            raise RuntimeError(\"Emby item query status \" + str(status))\n        page = json.loads(raw)\n        members = page.get(\"Items\", [])\n        report[\"readableTypes\"][kind] = bool(members and members[0].get(\"Type\") == kind)\n        del raw, page, members\nfinally:\n    status, _ = call(\"POST\", \"/Sessions/Logout\", {})\n    headers.clear()\n    report[\"loggedOut\"] = status in (200, 204)\n    print(json.dumps(report))\nif not report[\"loggedOut\"] or not all(report[\"readableTypes\"].values()):\n    raise SystemExit(1)"
credentials = json.loads(sys.stdin.readline())
assert set(credentials) == {"username", "password"}
target = ["ssh", "-T", "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=yes",
          "-o", "ConnectTimeout=8", '--', os.environ['YFUSE_QA_SSH_TARGET'], "python3 -c " + shlex.quote(REMOTE)]
try:
    p = subprocess.run(target, input=json.dumps(credentials), text=True, encoding="utf8",
                       capture_output=True, timeout=100)
finally:
    credentials.clear()
if not p.stdout.strip():
    raise SystemExit("The Emby server-side check returned no receipt (SSH exit " + str(p.returncode) + ")")
result = json.loads(p.stdout.strip())
public = {"run": 44417724795, "timeUtc": datetime.now(timezone.utc).isoformat(),
          "authenticated": result.get("authenticated") is True,
          "movieCatalogReadable": result.get("readableTypes", {}).get("Movie") is True,
          "episodeCatalogReadable": result.get("readableTypes", {}).get("Episode") is True,
          "sessionLoggedOut": result.get("loggedOut") is True}
if "httpStatus" in result and type(result["httpStatus"]) is int:
    public["httpStatus"] = result["httpStatus"]
print(json.dumps(public))
Path("audit/power-ui-20261116/emby-login-preflight-43.json").write_text(json.dumps(public, indent=2) + "\n", "utf8")
raise SystemExit(p.returncode)