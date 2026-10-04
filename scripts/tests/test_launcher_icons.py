"""Contract checks for the switchable launcher icons in Logo 与开屏动画 → APP 图标.

Choosing an icon enables one manifest component and disables the rest, so a variant whose
component is missing, enabled by default or not a launcher entry breaks the switch with no
compile error. The vector layers drawn by scripts/launcher_icons/generate.py are checked against
the limits the generator promises: lint reports a pathData longer than 800 characters as slow.
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


def resource(reference):
    """The file behind an @mipmap/ or @drawable/ reference; None for a framework resource."""
    if reference.startswith("@android:"):
        return None
    kind, name = re.fullmatch(r"@(\w+)/(\w+)", reference).groups()
    found = sorted(RES.glob(f"{kind}*/{name}.*"))
    return found[0] if found else Path(f"missing {reference}")


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


if __name__ == "__main__":
    unittest.main()
