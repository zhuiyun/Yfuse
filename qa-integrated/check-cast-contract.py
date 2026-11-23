"""Validate the device evidence required to exercise a separate DLNA receiver."""
from pathlib import Path
import re
import sys


CAST = "com.yfuse.core.cast.CastDiscoveryInstrumentedTest#device_discovery_can_be_refreshed_when_chromecast_is_unavailable"
CAPTIONS = "com.yfuse.feature.player.PlayerPanelNavigationInstrumentedTest#playback_problem_captions_are_readable_on_bright_and_dark_pictures"


def outcomes(text):
    completed = {}
    fields = {}
    for line in text.splitlines():
        value = re.fullmatch(r"INSTRUMENTATION_STATUS: (class|test)=(.+)", line)
        if value:
            fields[value[1]] = value[2]
        status = re.fullmatch(r"INSTRUMENTATION_STATUS_CODE: (-?\d+)", line)
        if status:
            code = int(status[1])
            if code <= 0 and fields.keys() >= {"class", "test"}:
                name = fields["class"] + "#" + fields["test"]
                if name in completed:
                    raise ValueError(f"Duplicate instrumentation completion for {name}")
                completed[name] = code
            fields = {}
    return completed


def verify(folder):
    current = (folder / "contracts.txt").read_text(encoding="utf8")
    old = (folder / "cast-discovery-negative.txt").read_text(encoding="utf8")
    cases = outcomes(current)
    failed = {name: code for name, code in cases.items() if code != 0}
    assert len(cases) == 16 and cases.get(CAST) == 0
    assert not failed or (set(failed) == {CAPTIONS} and failed[CAPTIONS] in (-1, -2)
                          and "Playback diagnostic captions below 4.5:1" in current)
    assert outcomes(old).get(CAST) in (-1, -2) and len(outcomes(old)) == 1
    assert "ModuleUnavailableException" in old
    return {"positiveDeviceCases": sum(code == 0 for code in cases.values()),
            "remainingFailures": sorted(failed), "castPassed": True, "oldCastFailedWithMissingModule": True}


if __name__ == "__main__":
    import json
    print(json.dumps(verify(Path(sys.argv[1])), indent=2))
