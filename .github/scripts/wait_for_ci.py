#!/usr/bin/env python3
"""Wait until backend CI is green for a production deploy SHA.

A merge commit on main often has no CI run of its own when the merger is a
GitHub App token. In that case this accepts a successful pull_request CI run
on the second parent (the PR head) after a short wait to see whether main CI
starts.
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
import time
from typing import Any

FAIL_CONCLUSIONS = {
    "failure",
    "cancelled",
    "timed_out",
    "startup_failure",
    "action_required",
}


def log(message: str) -> None:
    print(message, flush=True)


def gh_json(args: list[str]) -> Any:
    proc = subprocess.run(
        ["gh", *args],
        check=True,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    raw = proc.stdout.strip()
    return json.loads(raw) if raw else None


def ci_runs(repo: str, commit: str) -> list[dict[str, Any]]:
    rows = gh_json(
        [
            "run",
            "list",
            "--repo",
            repo,
            "--commit",
            commit,
            "--workflow",
            "CI",
            "--json",
            "conclusion,status,event,headBranch,databaseId,url",
            "--limit",
            "20",
        ]
    )
    return rows or []


def pick_run(runs: list[dict[str, Any]]) -> dict[str, Any] | None:
    if not runs:
        return None
    push_main = [
        row for row in runs if row.get("event") == "push" and row.get("headBranch") == "main"
    ]
    pull_requests = [row for row in runs if row.get("event") == "pull_request"]
    return (push_main or pull_requests or runs)[0]


def conclusion_of(repo: str, commit: str) -> tuple[str, dict[str, Any] | None]:
    run = pick_run(ci_runs(repo, commit))
    if not run:
        return "missing", None
    return (run.get("conclusion") or run.get("status") or "unknown"), run


def parents_of(repo: str, commit: str) -> list[str]:
    data = gh_json(["api", f"repos/{repo}/commits/{commit}"]) or {}
    return [parent["sha"] for parent in data.get("parents") or []]


def main() -> None:
    repo = os.environ.get("GITHUB_REPOSITORY") or ""
    sha = (os.environ.get("TARGET_SHA") or os.environ.get("GITHUB_SHA") or "").strip()
    if not repo:
        print("GITHUB_REPOSITORY is not set", file=sys.stderr)
        raise SystemExit(1)
    if not sha:
        print("TARGET_SHA is empty", file=sys.stderr)
        raise SystemExit(1)

    log(f"Waiting for CI on {sha}")
    deadline = time.time() + 30 * 60
    saw_green_pr_parent = False
    while time.time() < deadline:
        conclusion, run = conclusion_of(repo, sha)
        if run:
            log(f"CI on {sha}: {run}")
        else:
            log(f"CI on {sha}: missing")
        if conclusion == "success":
            log(f"CI green on {sha}")
            return
        if conclusion in FAIL_CONCLUSIONS:
            print(f"CI {conclusion} on {sha} — refusing to deploy", file=sys.stderr)
            raise SystemExit(1)

        pars = parents_of(repo, sha)
        if len(pars) >= 2:
            parent_sha = pars[1]
            parent_conclusion, parent_run = conclusion_of(repo, parent_sha)
            if parent_run:
                log(f"PR-head CI on {parent_sha}: {parent_run}")
            if parent_conclusion in FAIL_CONCLUSIONS:
                print(
                    f"PR CI {parent_conclusion} on {parent_sha} — refusing to deploy",
                    file=sys.stderr,
                )
                raise SystemExit(1)
            if parent_conclusion == "success" and conclusion == "missing":
                if saw_green_pr_parent:
                    log(
                        f"No CI run on merge SHA {sha}; accepting successful PR CI on {parent_sha}"
                    )
                    return
                saw_green_pr_parent = True
                log("PR CI is green; waiting briefly in case merge-commit CI starts")
                time.sleep(45)
                continue

        log(f"CI not finished ({conclusion}); sleeping")
        time.sleep(20)

    print("Timed out waiting for CI", file=sys.stderr)
    raise SystemExit(1)


if __name__ == "__main__":
    try:
        main()
    except subprocess.CalledProcessError as exc:
        detail = ((exc.stderr or exc.stdout) or str(exc)).strip()
        print(detail, file=sys.stderr)
        raise SystemExit(1) from exc
