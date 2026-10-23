"""Synthetic fixtures validate arithmetic only, never constitute device benchmarks."""

import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location(
    "hands_report", Path(__file__).resolve().parents[1] / "scripts/android/hands-report.py"
)
assert SPEC and SPEC.loader
REPORT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(REPORT)


class HandsReportTest(unittest.TestCase):
    def test_no_samples_is_pending_not_zero_latency(self):
        with tempfile.TemporaryDirectory() as directory:
            result = REPORT.report(Path(directory))
        self.assertEqual(result["status"], "pending_device_measurement")
        self.assertIsNone(result["latencies"]["instruction_to_first_action"]["p50_ms"])
        self.assertIsNone(result["success_rate"])

    def test_nearest_rank_and_no_double_counting(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "run-summary.json").write_text(
                json.dumps(
                    {
                        "elapsed_ms": 1000,
                        "outcome": "done",
                        "android_action_attempts": 1,
                        "milestones_ms": {"instruction_received": 0, "first_android_action": 400},
                    }
                ),
                encoding="utf-8",
            )
            (root / "trace.jsonl").write_text(
                json.dumps(
                    {
                        "step": 1,
                        "timings_ms": {
                            "model_ms": 200,
                            "android_action_ms": 100,
                            "settle_wait_ms": 300,
                            "action_and_wait_ms": 400,
                        },
                    }
                )
                + "\n",
                encoding="utf-8",
            )
            result = REPORT.report(root)
        self.assertEqual(result["latencies"]["instruction_to_first_action"]["p95_ms"], 400)
        self.assertEqual(result["operator_stage_share"]["unattributed_ms"]["total_ms"], 400)
        self.assertEqual(result["latencies"]["model_call"]["p50_ms"], 200)
        self.assertEqual(result["latencies"]["page_wait"]["p95_ms"], 300)
        self.assertEqual(REPORT.percentiles(list(range(1, 21)))["p95_ms"], 19)

    def test_legacy_summary_does_not_invent_instruction_time(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "run-summary.json").write_text(
                '{"elapsed_ms": 1000, "outcome": "failed"}', encoding="utf-8"
            )
            result = REPORT.report(root)
        self.assertEqual(result["latencies"]["instruction_to_first_action"]["n"], 0)
        self.assertIsNone(result["runs"][0]["android_action_attempts"])


if __name__ == "__main__":
    unittest.main()
