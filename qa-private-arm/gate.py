"""Require real reads, both active engines and both confirmed seeks per original."""
import runpy
state = runpy.run_path("qa-private-arm/test.py", run_name="__main__")
items, rows = state["items"], state["ROWS"]
opened = [r for r in rows if r["case"].endswith("-opened") and r.get("moving") and r.get("identity", {}).get("active")]
confirmed = [r for r in rows if r["case"].endswith("-confirmed")]
denied = sum(not all(v["correct"] for v in row.values()) for row in state["groups"].values())
print("EMBY_GATE", "originals", len(items), "denied", denied, "opened", len(opened), "confirmed", len(confirmed), flush=True)
raise SystemExit(int(denied != 0 or len(opened) != len(items) * 2 or len(confirmed) != len(items) * 4))
