"""Run a reviewed local UTF-8 Bash script through SSH without shell interpolation."""

from pathlib import Path
import subprocess
import sys

script = Path(sys.argv[1])
data = script.read_bytes().replace(b"\r\n", b"\n")
arguments = sys.argv[2:]
if any(not argument or any(character not in "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789/._:-" for character in argument) for argument in arguments):
    raise SystemExit("Unsafe remote script argument")
command = [
    "ssh", "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=yes", "-o", "ConnectTimeout=12",
    "root@47.112.219.60", "bash -s -- " + " ".join(arguments),
]
process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
process.stdin.write(data)
process.stdin.close()
with script.with_suffix(".log").open("wb") as log:
    for line in process.stdout:
        log.write(line)
        log.flush()
        sys.stdout.buffer.write(line)
        sys.stdout.buffer.flush()
sys.exit(process.wait())
