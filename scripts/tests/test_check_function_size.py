"""Contract checks for scripts/check_function_size.py."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / "check_function_size.py"
SPEC = importlib.util.spec_from_file_location("check_function_size", SCRIPT)
gate = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(gate)


class FunctionSizeGateTest(unittest.TestCase):
    def test_ycore_sources_have_the_tighter_limit(self):
        self.assertEqual(200, gate.limit_for("composeApp/src/androidMain/kotlin/com/yfuse/core2/android/A.kt"))
        self.assertEqual(200, gate.limit_for("composeApp/src/commonMain/kotlin/com/yfuse/core2/api/YPlayer.kt"))
        self.assertEqual(
            gate.MAX_LINES,
            gate.limit_for("composeApp/src/androidMain/kotlin/com/yfuse/feature/player/PlayerRoot.kt"),
        )

    def test_a_body_is_measured_with_braces_in_strings_and_comments_ignored(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "Sample.kt"
            path.write_text(
                'fun short() = 1\n\nfun body() {\n    val text = "}{"\n    // }\n    call()\n}\n',
                encoding="utf-8",
            )
            self.assertEqual([("body", 3, 5)], gate.function_lengths(str(path)))


if __name__ == "__main__":
    unittest.main()
