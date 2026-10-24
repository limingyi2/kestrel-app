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

    def test_native_plan_counts_and_timings_are_reported(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "run-summary.json").write_text(
                json.dumps(
                    {
                        "elapsed_ms": 100,
                        "outcome": "done",
                        "screenshot_count": 2,
                        "visual_model_calls": 1,
                        "native_plan_count": 1,
                        "native_plan_actions_completed": 2,
                        "native_plan_fallback_count": 0,
                    }
                ),
                encoding="utf-8",
            )
            (root / "trace.jsonl").write_text(
                '{"timings_ms": {"model_ms": 40, "native_node_lookup_ms": 10, '
                '"native_action_ms": 20, "page_wait_ms": 30}}\n',
                encoding="utf-8",
            )
            result = REPORT.report(root)
        self.assertEqual(result["runs"][0]["screenshot_count"], 2)
        self.assertEqual(result["runs"][0]["visual_model_calls"], 1)
        self.assertEqual(result["runs"][0]["native_plan_actions_completed"], 2)
        self.assertEqual(result["latencies"]["page_wait"]["p50_ms"], 30)
        self.assertEqual(result["operator_stage_share"]["native_action_ms"]["total_ms"], 20)

    def test_legacy_summary_does_not_invent_instruction_time(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "run-summary.json").write_text(
                '{"elapsed_ms": 1000, "outcome": "failed"}', encoding="utf-8"
            )
            result = REPORT.report(root)
        self.assertEqual(result["latencies"]["instruction_to_first_action"]["n"], 0)
        self.assertIsNone(result["runs"][0]["android_action_attempts"])

    def test_end_to_end_shares_include_agent_and_dispatch_before_operator(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "run-summary.json").write_text(
                json.dumps(
                    {
                        "elapsed_ms": 500,
                        "outcome": "done",
                        "milestones_ms": {
                            "instruction_received": 0,
                            "hands_started": 400,
                            "task_finished": 900,
                            "agent_task_finished": 1000,
                        },
                    }
                ),
                encoding="utf-8",
            )
            (root / "trace.jsonl").write_text(
                '{"timings_ms": {"model_ms": 500}}\n', encoding="utf-8"
            )
            result = REPORT.report(root)
        shares = result["end_to_end_stage_share"]
        self.assertEqual(shares["pre_operator_ms"]["percent"], 40)
        self.assertEqual(shares["model_ms"]["percent"], 50)
        self.assertEqual(shares["main_agent_tail_ms"]["percent"], 10)
        self.assertEqual(shares["unattributed_ms"]["percent"], 0)


if __name__ == "__main__":
    unittest.main()
