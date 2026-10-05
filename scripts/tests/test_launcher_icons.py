"""Contract checks for the switchable launcher icons in Logo 与开屏动画 → APP 图标.

Choosing an icon enables one manifest component and disables the rest, so a variant whose
component is missing, enabled by default or not a launcher entry breaks the switch with no
compile error. The launcher shortcuts and the 追剧更新 notification draw the chosen icon too,
through resource mappings that nothing else checks against the manifest. The vector layers drawn
by scripts/launcher_icons/generate.py are checked against the limits the generator promises:
lint reports a pathData longer than 800 characters as slow. Their marks are checked to sit in the
middle of the icon, since a mark leaning to one side passes everything else and shows only on the
home screen.
"""
import re
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ANDROID = ROOT / "composeApp/src/androidMain"
RES = ANDROID / "res"
A = "{http://schemas.android.com/apk/res/android}"
GENERATED = ("prism", "water_over_fire", "overprint", "danmaku", "liquid_glass")


def variants():
    common = (ROOT / "composeApp/src/commonMain/kotlin/com/yfuse/feature/profile/AppIconVariant.kt").read_text("utf-8")
    body = common.split("enum class AppIconVariant(", 1)[1]
    return re.findall(r"^\s{4}(\w+)\(\"", body, re.M)


def components():
    android = (ANDROID / "kotlin/com/yfuse/feature/profile/AppIconVariant.android.kt").read_text("utf-8")
    return dict(re.findall(r"AppIconVariant\.(\w+) -> \"([\w.]+)\"", android))


def resources(function):
    """The branches of one `AppIconVariant.<function>(): Int` mapping, as variant -> @type/name."""
    android = (ANDROID / "kotlin/com/yfuse/feature/profile/AppIconVariant.android.kt").read_text("utf-8")
    body = android.split(f"fun AppIconVariant.{function}(): Int =", 1)[1].split("\n    }\n", 1)[0]
    found = {}
    for names, kind, name in re.findall(r"^\s+([\w., ]+) -> R\.(\w+)\.(\w+)$", body, re.M):
        for variant in re.findall(r"AppIconVariant\.(\w+)", names):
            found[variant] = f"@{kind}/{name}"
    return found


def resource(reference):
    """The file behind an @mipmap/ or @drawable/ reference; None for a framework resource."""
    if reference.startswith("@android:"):
        return None
    kind, name = re.fullmatch(r"@(\w+)/(\w+)", reference).groups()
    found = sorted(RES.glob(f"{kind}*/{name}.*"))
    return found[0] if found else Path(f"missing {reference}")


def balance(layer):
    """How far right of the layer's centre its mark's box centre and its area centroid sit.

    The generator writes each ring as `Mx,y` and integer `l` steps. Rows two units apart are filled
    even-odd, which matches the nonzero rule for rings that do not overlap.
    """
    root = ET.parse(layer).getroot()
    rings = []
    for element in root.iter("path"):
        for x, y, steps in re.findall(r"M(-?\d+),(-?\d+)l([^z]*)z", element.get(A + "pathData")):
            points = [(int(x), int(y))]
            for dx, dy in re.findall(r"(-?\d+),(-?\d+)", steps):
                points.append((points[-1][0] + int(dx), points[-1][1] + int(dy)))
            rings.append(points)
    xs = [x for ring in rings for x, _ in ring]
    ys = [y for ring in rings for _, y in ring]
    area = moment = 0.0
    for row in range(min(ys), max(ys), 2):
        y = row + 0.5
        cuts = sorted(
            x1 + (y - y1) * (x2 - x1) / (y2 - y1)
            for ring in rings
            for (x1, y1), (x2, y2) in zip(ring, ring[1:] + ring[:1])
            if (y1 <= y) != (y2 <= y)
        )
        for a, b in zip(cuts[::2], cuts[1::2]):
            area += b - a
            moment += (b * b - a * a) / 2
    centre = float(root.get(A + "viewportWidth")) / 2
    return (min(xs) + max(xs)) / 2 - centre, moment / area - centre


class LauncherIconTest(unittest.TestCase):
    def test_every_variant_has_a_disabled_launcher_alias(self):
        application = ET.parse(ANDROID / "AndroidManifest.xml").getroot().find("application")
        aliases = {alias.get(A + "name"): alias for alias in application.findall("activity-alias")}
        mapping = components()
        names = variants()
        self.assertIn("Default", names)
        self.assertEqual(len(set(mapping.values())), len(mapping), "two variants share a component")
        for variant in names:
            with self.subTest(variant=variant):
                self.assertIn(variant, mapping, "no componentClass() branch")
                component = mapping[variant]
                if component == "com.yfuse.MainActivity":
                    continue
                alias = aliases.get(component)
                self.assertIsNotNone(alias, f"{component} is not in the manifest")
                self.assertEqual(alias.get(A + "targetActivity"), "com.yfuse.MainActivity")
                self.assertEqual(alias.get(A + "enabled"), "false", "two drawer entries on a fresh install")
                categories = {c.get(A + "name") for f in alias.findall("intent-filter") for c in f.iter("category")}
                self.assertIn("android.intent.category.LAUNCHER", categories)
                for attribute in ("icon", "roundIcon"):
                    icon = resource(alias.get(A + attribute))
                    self.assertTrue(icon.exists(), f"{component} {attribute}: {icon}")
                    if icon.suffix != ".xml":
                        continue
                    for layer in ET.parse(icon).getroot():
                        target = resource(layer.get(A + "drawable"))
                        self.assertTrue(target is None or target.exists(), f"{icon.name}: {target}")

    def test_shortcuts_and_notifications_draw_the_chosen_icon(self):
        application = ET.parse(ANDROID / "AndroidManifest.xml").getroot().find("application")
        entries = {e.get(A + "name"): e for e in [*application.findall("activity"), *application.findall("activity-alias")]}
        mapping = components()
        launcher = resources("launcherIcon")
        small = resources("notificationIcon")
        for variant in variants():
            with self.subTest(variant=variant):
                # MainActivity has no icon of its own; the launcher shows the application's.
                entry = entries[mapping[variant]]
                self.assertEqual(launcher.get(variant), entry.get(A + "icon") or application.get(A + "icon"))
                icon = resource(small.get(variant, "@drawable/missing"))
                self.assertTrue(icon.exists(), f"{variant}: {icon}")
                root = ET.parse(icon).getroot()
                self.assertEqual(root.tag, "vector")
                self.assertEqual((root.get(A + "width"), root.get(A + "height")), ("24dp", "24dp"))
                self.assertEqual(root.get(A + "viewportWidth"), root.get(A + "viewportHeight"))
                # Android keeps only the alpha of a small icon: one opaque colour, no gradient.
                self.assertNotIn("aapt:attr", icon.read_text("utf-8"))
                for element in root.iter("path"):
                    self.assertEqual(element.get(A + "fillColor"), "#FFFFFFFF")
                    self.assertLessEqual(len(element.get(A + "pathData")), 800)

    def test_generated_vector_layers_stay_within_their_limits(self):
        for key in GENERATED:
            for layer in ("background", "foreground", "mono"):
                path = RES / f"drawable/ic_{key}_{layer}.xml"
                with self.subTest(file=path.name):
                    self.assertIn("scripts/launcher_icons/generate.py", path.read_text("utf-8"))
                    root = ET.parse(path).getroot()
                    self.assertEqual(root.get(A + "viewportWidth"), "1536")
                    paths = list(root.iter("path"))
                    self.assertTrue(paths)
                    for element in paths:
                        self.assertLessEqual(len(element.get(A + "pathData")), 800)
                    for element in root.iter():
                        for name, value in element.attrib.items():
                            if name.lower().endswith("color"):
                                self.assertRegex(value, r"^#[0-9A-F]{8}$")
                    if layer == "mono":
                        self.assertNotIn("aapt:attr", path.read_text("utf-8"), "a themed layer is one colour")

    def test_generated_marks_sit_in_the_middle(self):
        # The eye puts a mark's middle between its box and its centroid: a play triangle centred by
        # its box leans left, and one centred by its centroid has its point near the edge. 水火既济
        # once sat 52 units right of the middle, on a canvas of 1024.
        for key in GENERATED:
            with self.subTest(icon=key):
                box, centroid = balance(RES / f"drawable/ic_{key}_mono.xml")
                self.assertLessEqual(abs((box + centroid) / 2), 16, f"box {box:+.0f}, centroid {centroid:+.0f}")


if __name__ == "__main__":
    unittest.main()
