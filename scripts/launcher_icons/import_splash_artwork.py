"""Import the approved player-splash PNG layers; cache small blur mipmaps for Android 8+.

Usage: python import_splash_artwork.py <player-splash/assets>
No redesign: all layers retain their common registration and original aspect ratio.
"""

import sys
import re
from pathlib import Path

from PIL import Image, ImageFilter

source = Path(sys.argv[1])
target = Path(__file__).resolve().parents[2] / "composeApp/src/androidMain/res/drawable-nodpi"
target.mkdir(parents=True, exist_ok=True)
for name in ("logo", "blue", "orange", "gold", "outline"):
    filename = "logo.png" if name == "logo" else f"logo-{name}.png"
    original = Image.open(source / filename).convert("RGBA")
    # Equal transparent margins leave room for the blur without clipping the silhouette.
    original.thumbnail((432, 432), Image.Resampling.LANCZOS)
    aligned = Image.new("RGBA", (512, 512))
    aligned.alpha_composite(original, ((512 - original.width) // 2, (512 - original.height) // 2))
    aligned.save(target / f"water_fire_{name}.png", optimize=True)
    if name != "outline":
        for radius in (8, 24):
            aligned.filter(ImageFilter.GaussianBlur(radius)).resize((256, 256), Image.Resampling.LANCZOS).save(
                target / f"water_fire_{name}_blur{radius}.png", optimize=True
            )
print(f"Imported registered artwork to {target}")

# Keep the approved sampling, including the domino tiles' exact colors and spacing.
points = (source / "dots.js").read_text(encoding="utf-8")
raw_target = target.parent / "raw"
raw_target.mkdir(exist_ok=True)
for kind, key in (("beads", "BEADS"), ("sand", "SAND"), ("domino", "TILES")):
    match = re.search(r"window\.LOGO_" + key + r"\s*=\s*['\"]([^'\"]+)['\"]", points)
    if match is None:
        raise ValueError(f"Missing {kind} samples in dots.js")
    rows = match.group(1).split("|")
    (raw_target / f"water_fire_{kind}.csv").write_text("\n".join(rows) + "\n", encoding="utf-8")
    print(f"Imported {len(rows)} {kind} samples")
