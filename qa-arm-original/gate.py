"""Every requested original must start and pass both measured seeks."""
import runpy
state = runpy.run_path("qa-arm-original/direct.py", run_name="__main__")
requested = len(state["rows"][state["shard"]::state["n"]])
completed = sum(row["case"].endswith("-complete") and row.get("ok", False) for row in state["ROWS"])
print("ORIGINAL_GATE", "requested", requested, "completed", completed, flush=True)
raise SystemExit(int(completed != requested))
