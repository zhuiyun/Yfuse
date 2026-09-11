"""Keep TV presentation out of the phone compilation, without requiring Android tools."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def verify(root: Path = ROOT) -> None:
    for source_set in ("androidMain", "androidUnitTest"):
        old = root / "composeApp/src" / source_set / "kotlin/com/yfuse/tv/ui"
        if old.exists() and any(old.rglob("*.kt")):
            raise SystemExit(f"TV presentation belongs to tvApp, not {old.relative_to(root)}")
    phone = root / "composeApp/src/androidMain/kotlin"
    offenders = [
        str(path.relative_to(root)) for path in phone.rglob("*.kt")
        if "import com.yfuse.tv.ui." in path.read_text(encoding="utf-8")
    ]
    if offenders:
        raise SystemExit("Phone imports TV presentation: " + ", ".join(offenders))
    print("Verified phone/TV presentation boundary")


if __name__ == "__main__":
    verify()
