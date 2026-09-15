#!/usr/bin/env python3
"""Summarize Flutter's machine reporter without guessing test counts."""

from __future__ import annotations

import argparse
import io
import json
import os
import sys
from collections.abc import Iterable


def summarize(lines: Iterable[str]) -> tuple[int, int, int, int, bool]:
    skipped_at_start: dict[int, bool] = {}
    results: dict[int, tuple[str, bool]] = {}
    run_succeeded: bool | None = None

    for raw in lines:
        raw = raw.strip()
        if not raw:
            continue
        try:
            event = json.loads(raw)
        except json.JSONDecodeError:
            continue
        # Flutter may interleave VM service protocol messages such as
        # ``[{"event":"test.startedProcess", ...}]`` with the machine
        # reporter's object events. They are valid JSON but are not test
        # result records, so ignore them instead of aborting the summary.
        if not isinstance(event, dict):
            continue
        event_type = event.get("type")
        if event_type == "testStart":
            test = event.get("test") or {}
            test_id = test.get("id")
            if isinstance(test_id, int):
                skipped_at_start[test_id] = bool(
                    (test.get("metadata") or {}).get("skip")
                )
        elif event_type == "testDone":
            test_id = event.get("testID")
            if not isinstance(test_id, int) or bool(event.get("hidden")):
                continue
            results[test_id] = (
                str(event.get("result") or "error").lower(),
                bool(event.get("skipped")) or skipped_at_start.get(test_id, False),
            )
        elif event_type == "done":
            run_succeeded = bool(event.get("success"))

    passed = failed = skipped = 0
    for result, was_skipped in results.values():
        if was_skipped:
            skipped += 1
        elif result == "success":
            passed += 1
        else:
            failed += 1
    total = passed + failed + skipped
    return total, passed, failed, skipped, run_succeeded is True


def self_test() -> None:
    data = """
{"type":"testStart","test":{"id":1,"name":"passes"}}
{"type":"testDone","testID":1,"result":"success","hidden":false,"skipped":false}
{"type":"testStart","test":{"id":2,"name":"skips"}}
{"type":"testDone","testID":2,"result":"success","hidden":false,"skipped":true}
{"type":"testStart","test":{"id":3,"name":"fails"}}
{"type":"testDone","testID":3,"result":"failure","hidden":false,"skipped":false}
{"type":"testDone","testID":99,"result":"success","hidden":true,"skipped":false}
[{"event":"test.startedProcess","params":{"vmServiceUri":null}}]
{"type":"done","success":false}
"""
    assert summarize(io.StringIO(data)) == (3, 1, 1, 1, False)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("event_file", nargs="?")
    parser.add_argument("--app", default="unknown")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()

    if args.self_test:
        self_test()
        print("Flutter test event summarizer self-test: PASS")
        return 0
    if not args.event_file:
        parser.error("event_file is required unless --self-test is used")

    with open(args.event_file, encoding="utf-8") as events:
        total, passed, failed, skipped, run_succeeded = summarize(events)
    line = (
        f"FLUTTER_TEST_SUMMARY app={args.app} tests={total} "
        f"pass={passed} fail={failed} skipped={skipped}"
    )
    print(line)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as out:
            out.write(line + "\n")
    if total == 0:
        print("No non-hidden Flutter tests were reported.", file=sys.stderr)
        return 1
    return 0 if run_succeeded and failed == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
