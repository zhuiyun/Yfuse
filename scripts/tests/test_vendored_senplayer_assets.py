import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


class RemovedPlaybackRuntimeTest(unittest.TestCase):
    def test_standard_application_does_not_include_the_old_runtime_module_or_assets(self):
        gradle_settings = (ROOT / "settings.gradle.kts").read_text(encoding="utf-8")
        self.assertNotIn('include(":senplayerBridge")', gradle_settings)
        for module in ("phoneShared", "tvShared"):
            dependencies = (ROOT / module / "build.gradle.kts").read_text(encoding="utf-8")
            self.assertNotIn('project(":senplayerBridge")', dependencies)
        self.assertFalse((ROOT / "senplayerBridge/src/main/assets/senplayer/runtime.json").exists())


if __name__ == "__main__":
    unittest.main()
