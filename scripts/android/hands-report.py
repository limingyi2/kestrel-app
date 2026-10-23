"""Summarize existing Hands trace/summary pairs. No device samples are synthesized."""

from __future__ import annotations

import argparse
import json
import math
from pathlib import Path
from typing import Any

STAGES = (
    ("agent_response", "instruction_received", "agent_first_response"),
    ("agent_to_hands_call", "agent_first_response", "hands_tool_call"),
    ("tool_scheduling", "hands_tool_call", "hands_native_received"),
    ("native_startup", "hands_native_received", "hands_started"),
    ("operator_to_first_screenshot", "hands_started", "first_screenshot_complete"),
    ("screenshot_to_visual_response", "first_screenshot_complete", "first_visual_response"),
    ("first_to_next_action", "first_android_action", "next_android_action"),
    ("instruction_to_first_action", "instruction_received", "first_android_action"),
    ("task_total", "instruction_received", "task_finished"),
)
# Exclusive leaf durations only. action_and_wait_ms contains several of these, never sum it.
LEAVES = (
    "screenshot_capture_ms",
    "screenshot_retry_wait_ms",
    "screenshot_encode_ms",
    "screenshot_save_ms",
    "model_ms",
    "accessibility_lookup_ms",
    "risk_approval_ms",
    "aim_wait_ms",
    "android_action_ms",
    "settle_wait_ms",
    "explicit_wait_ms",
)


def percentiles(values: list[float]) -> dict[str, Any]:
    ordered = sorted(values)
    if not ordered:
        return {"n": 0, "p50_ms": None, "p95_ms": None, "status": "pending_device_measurement"}
    return {
        "n": len(ordered),
        "p50_ms": ordered[math.ceil(len(ordered) * 0.50) - 1],
        "p95_ms": ordered[math.ceil(len(ordered) * 0.95) - 1],
    }


def report(root: Path) -> dict[str, Any]:
    distributions: dict[str, list[float]] = {name: [] for name, _, _ in STAGES}
    distributions.update({"model_call": [], "page_wait": []})
    totals = dict.fromkeys(LEAVES, 0.0)
    runs = []
    elapsed = 0.0
    for path in sorted(root.rglob("run-summary.json")):
        summary = json.loads(path.read_text(encoding="utf-8"))
        milestones = summary.get("milestones_ms", {})
        for name, start, end in STAGES:
            if start in milestones and end in milestones:
                distributions[name].append(milestones[end] - milestones[start])
        duration = summary["elapsed_ms"]
        elapsed += duration
        rows = []
        trace = path.with_name("trace.jsonl")
        for line in trace.read_text(encoding="utf-8").splitlines() if trace.exists() else []:
            row = json.loads(line)
            times = row.get("timings_ms", {})
            for key in LEAVES:
                totals[key] += times.get(key, 0)
            for name, field in (("model_call", "model_ms"), ("page_wait", "settle_wait_ms")):
                if field in times:
                    distributions[name].append(times[field])
            rows.append({"step": row.get("step"), "timings_ms": times})
        runs.append(
            {
                "run": path.parent.name,
                "outcome": summary.get("outcome"),
                "android_action_attempts": summary.get("android_action_attempts"),
                "foreground_confirmed": summary.get("foreground_confirmed"),
                "return_requested": summary.get("return_requested"),
                "mistaps_or_duplicate_taps": "requires_device_observer_annotation",
                "steps": rows,
            }
        )
    counted = sum(totals.values())
    return {
        "status": "measured_traces" if runs else "pending_device_measurement",
        "percentile_method": "nearest_rank",
        "runs": runs,
        "latencies": {key: percentiles(value) for key, value in distributions.items()},
        "operator_stage_share": {
            key: {"total_ms": value, "percent": value / elapsed * 100 if elapsed else None}
            for key, value in {**totals, "unattributed_ms": max(0, elapsed - counted)}.items()
        },
        "success_rate": sum(r["outcome"] == "done" for r in runs) / len(runs) if runs else None,
        "success_definition": "operator_done; verify Android version against device before acceptance",
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    args = parser.parse_args()
    print(json.dumps(report(args.directory), ensure_ascii=False, indent=2))
