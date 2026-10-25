"""Fetch the two already-identified public originals; fail on changed bytes."""
from pathlib import Path
import hashlib
import urllib.request

ASSETS = Path(__file__).parent / "app/src/main/assets"
INPUTS = {
    "whalefall": (
        "https://lumiere-a.akamaihd.net/v1/images/p_20cs_whalefall_teaser_1ce327a9.jpeg",
        "179392498e75a163de739a7c442fcdc896726c3a01527b085774534706d00034",
    ),
    "heart-of-the-beast": (
        "https://img1.gamersky.com/upimg/pic/2026/09/03/origin_202609031112263168.jpg",
        "b0ac5dca70214f0de4f634b294d48114d6be9b96b742be14e51aca757ad08a9b",
    ),
}

if __name__ == "__main__":
    ASSETS.mkdir(parents=True, exist_ok=True)
    for name, (url, sha) in INPUTS.items():
        target = ASSETS / f"{name}.jpg"
        data = target.read_bytes() if target.exists() else urllib.request.urlopen(url, timeout=60).read()
        if hashlib.sha256(data).hexdigest() != sha:
            raise SystemExit(f"Changed source bytes: {name}")
        target.write_bytes(data)
        print(f"Verified {name}: {len(data)} bytes, SHA-256 {sha}")
