#!/usr/bin/env python3
"""Merge an eligible PR into main after required CI is green.

Prefers GitHub-native auto-merge (`gh pr merge --auto`). If Allow auto-merge
is off, merges only after required checks succeed so the Merge button is not
required.

Never merges drafts, fork PRs, conflicted PRs, PRs with failing checks, or
PRs whose reviewDecision is REVIEW_REQUIRED / CHANGES_REQUESTED.
Does not approve PRs and does not bypass branch protection.
"""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import time
from typing import Any

REQUIRED_CHECK_NAMES = ("build-and-test", "schema-migrate")
IGNORE_CHECK_NAMES = {
    "enable github auto-merge",
    "enable auto-merge",
    "auto-merge eligible prs",
    "auto-merge eligible pr",
    "auto-merge eligible pr into main",
    # APK builds are a separate release artifact. A missing Play keystore
    # or Flutter flake must not block merging a backend fix.
    "build-apk",
    "build apk and deploy web",
}
FAIL_CONCLUSIONS = {
    "FAILURE",
    "CANCELLED",
    "TIMED_OUT",
    "STARTUP_FAILURE",
    "ACTION_REQUIRED",
}


def log(message: str) -> None:
    print(message, flush=True)


def fail(message: str) -> None:
    print(f"::error::{message}", flush=True)
    raise SystemExit(1)


def admin_settings_error(detail: str) -> None:
    fail(
        "GitHub Actions could not merge this PR. An admin must enable:\n"
        "1. Settings → Actions → General → Workflow permissions → Read and write permissions\n"
        "2. Settings → Actions → General → Allow GitHub Actions to create and approve pull requests\n"
        "3. Settings → General → Pull Requests → Allow auto-merge "
        "(native queued auto-merge; recommended)\n"
        "Required reviews and required status checks, if configured, stay in force. "
        "This workflow does not bypass branch protection and does not approve PRs.\n"
        f"Detail: {detail}"
    )


def gh(*args: str, check: bool = True) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["gh", *args],
        check=check,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )


def gh_json(args: list[str]) -> Any:
    proc = gh(*args, check=True)
    raw = proc.stdout.strip()
    return json.loads(raw) if raw else None


def norm(value: Any) -> str:
    return str(value or "").strip().upper()


def ignored_check(name: str) -> bool:
    return name.strip().lower() in IGNORE_CHECK_NAMES


def classify_checks(rollup: list[dict[str, Any]] | None) -> tuple[list[str], list[str], list[str]]:
    """Return (missing_required, failing, pending_required)."""
    rollup = rollup or []
    by_name: dict[str, dict[str, Any]] = {}
    failing: list[str] = []
    for item in rollup:
        name = str(item.get("name") or "")
        if not name or ignored_check(name):
            continue
        by_name[name] = item
        conclusion = norm(item.get("conclusion"))
        if conclusion in FAIL_CONCLUSIONS:
            failing.append(f"{name}={conclusion}")
    missing_required: list[str] = []
    pending_required: list[str] = []
    for required in REQUIRED_CHECK_NAMES:
        item = by_name.get(required)
        if item is None:
            missing_required.append(required)
            continue
        conclusion = norm(item.get("conclusion"))
        if conclusion == "SUCCESS":
            continue
        if conclusion in FAIL_CONCLUSIONS:
            continue
        pending_required.append(required)
    return missing_required, failing, pending_required


def find_pr(pr: int | None, sha: str | None) -> int:
    repo = os.environ["GITHUB_REPOSITORY"]
    if pr:
        return pr
    if not sha:
        fail("Pass --pr or --sha")
    rows = gh_json(
        [
            "pr",
            "list",
            "--repo",
            repo,
            "--state",
            "open",
            "--base",
            "main",
            "--json",
            "number,headRefOid",
        ]
    ) or []
    matches = [row for row in rows if row.get("headRefOid") == sha]
    if not matches:
        log(f"No open PR into main for SHA {sha}; nothing to merge.")
        raise SystemExit(0)
    return int(matches[0]["number"])


def pr_view(number: int) -> dict[str, Any]:
    repo = os.environ["GITHUB_REPOSITORY"]
    data = gh_json(
        [
            "pr",
            "view",
            str(number),
            "--repo",
            repo,
            "--json",
            "number,url,state,isDraft,mergeable,mergeStateStatus,reviewDecision,"
            "statusCheckRollup,headRefOid,headRepositoryOwner,headRepository,"
            "baseRefName,autoMergeRequest,isCrossRepository",
        ]
    )
    if not data:
        fail(f"PR #{number} not found")
    return data


def same_repo(data: dict[str, Any]) -> bool:
    repo = os.environ["GITHUB_REPOSITORY"]
    owner, name = repo.split("/", 1)
    head_owner = (data.get("headRepositoryOwner") or {}).get("login") or ""
    head_name = (data.get("headRepository") or {}).get("name") or ""
    if data.get("isCrossRepository"):
        return False
    if head_owner and head_name:
        return head_owner == owner and head_name == name
    return True


def dispatch_production_deploy() -> None:
    _dispatch_workflow(
        "deploy-production.yml",
        "Dispatched Deploy Production on main (backup if the merge push did not start it).",
        "Could not dispatch Deploy Production from GITHUB_TOKEN. "
        "If no push-triggered run starts, an admin must grant "
        "Settings → Actions → General → Workflow permissions → Read and write, "
        "or run Actions → Deploy Production → Run workflow on main.",
    )


def dispatch_production_apk() -> None:
    """GITHUB_TOKEN merges do not start push workflows. Dispatch the APK job."""
    _dispatch_workflow(
        "build-and-deploy.yml",
        "Dispatched Build APK and Deploy Web on main so a downloadable "
        "gpstore-production-latest.apk is produced for this release.",
        "Could not dispatch Build APK and Deploy Web from GITHUB_TOKEN. "
        "Run Actions → Build APK and Deploy Web → Run workflow on main.",
    )


def _dispatch_workflow(workflow_file: str, ok_message: str, fail_prefix: str) -> None:
    repo = os.environ["GITHUB_REPOSITORY"]
    proc = gh(
        "workflow",
        "run",
        workflow_file,
        "--repo",
        repo,
        "--ref",
        "main",
        check=False,
    )
    if proc.returncode == 0:
        log(ok_message)
        return
    log(f"{fail_prefix}\n{(proc.stderr or proc.stdout or '').strip()}")


def try_native_automerge(number: int) -> tuple[bool, str]:
    repo = os.environ["GITHUB_REPOSITORY"]
    proc = gh(
        "pr",
        "merge",
        str(number),
        "--repo",
        repo,
        "--merge",
        "--auto",
        check=False,
    )
    return proc.returncode == 0, ((proc.stdout or "") + (proc.stderr or "")).strip()


def try_merge_now(number: int) -> tuple[bool, str]:
    repo = os.environ["GITHUB_REPOSITORY"]
    proc = gh(
        "pr",
        "merge",
        str(number),
        "--repo",
        repo,
        "--merge",
        check=False,
    )
    return proc.returncode == 0, ((proc.stdout or "") + (proc.stderr or "")).strip()


def is_permission_error(text: str) -> bool:
    lowered = text.lower()
    return any(
        needle in lowered
        for needle in (
            "resource not accessible by integration",
            "github actions is not permitted",
            "not authorized",
            "http 403",
            "forbidden",
            "does not have permission",
            "review is required",
            "required status check",
        )
    )


def merge_eligible_pr(number: int, *, ci_already_green: bool = False) -> str:
    """Return merged, queued, skip, or wait."""
    data = pr_view(number)
    url = data.get("url")
    log(f"Evaluating {url}")
    if data.get("state") == "MERGED":
        log("Already merged.")
        return "skip"
    if data.get("state") != "OPEN":
        log(f"PR state is {data.get('state')}; skipping.")
        return "skip"
    if data.get("isDraft"):
        log("Draft PR; skipping.")
        return "skip"
    if data.get("baseRefName") != "main":
        log("Base branch is not main; skipping.")
        return "skip"
    if not same_repo(data):
        log("Fork PR; skipping.")
        return "skip"
    review = data.get("reviewDecision") or ""
    if review in {"REVIEW_REQUIRED", "CHANGES_REQUESTED"}:
        log(f"reviewDecision={review}; not merging (branch protection / reviews preserved).")
        return "skip"
    if data.get("mergeable") == "CONFLICTING" or data.get("mergeStateStatus") == "DIRTY":
        log("PR has conflicts; not merging.")
        return "skip"

    missing, failing, pending = classify_checks(data.get("statusCheckRollup"))
    if failing:
        log("Not merging; failing checks: " + ", ".join(failing))
        return "skip"
    if (missing or pending) and not ci_already_green:
        log(
            "Required CI is not green yet "
            f"(missing={missing or '-'} pending={pending or '-'}). "
            "Will merge when workflow CI completes."
        )
        return "wait"
    if ci_already_green and (missing or pending):
        log(
            "Workflow CI already succeeded on this SHA; "
            f"rollup still missing/pending {missing + pending}. Proceeding."
        )

    native_ok, native_text = try_native_automerge(number)
    if native_ok:
        log(native_text or f"Native auto-merge enabled for PR #{number}.")
        refreshed = pr_view(number)
        if refreshed.get("state") == "MERGED":
            dispatch_production_deploy()
            dispatch_production_apk()
            return "merged"
        log("GitHub will merge when remaining required ruleset checks pass.")
        return "queued"

    log(f"Native auto-merge not available: {native_text}")
    if "auto merge is not allowed" in native_text.lower() or "allow auto-merge" in native_text.lower():
        log("Allow auto-merge is off. Merging now because required CI is already green.")

    merged_ok, merged_text = try_merge_now(number)
    if merged_ok:
        log(merged_text or f"Merged PR #{number} into main.")
        dispatch_production_deploy()
        dispatch_production_apk()
        return "merged"

    if is_permission_error(merged_text) or is_permission_error(native_text):
        admin_settings_error(merged_text or native_text)
    fail(f"Could not merge PR #{number}: {merged_text or native_text}")
    return "skip"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--pr", type=int, default=0)
    parser.add_argument("--sha", default="")
    parser.add_argument("--wait-seconds", type=int, default=90)
    parser.add_argument(
        "--ci-already-green",
        action="store_true",
        help="Set when workflow CI already completed successfully for this SHA.",
    )
    args = parser.parse_args()
    if "GITHUB_REPOSITORY" not in os.environ:
        fail("GITHUB_REPOSITORY is not set")
    if "GH_TOKEN" not in os.environ and "GITHUB_TOKEN" not in os.environ:
        fail("GH_TOKEN / GITHUB_TOKEN is not set")

    number = find_pr(args.pr or None, args.sha or None)
    deadline = time.time() + max(args.wait_seconds, 0)
    while True:
        result = merge_eligible_pr(number, ci_already_green=args.ci_already_green)
        if result != "wait" or time.time() >= deadline:
            if result == "wait":
                log("Timed out waiting for required CI; not merging.")
            return
        log("Waiting for required checks to appear in the rollup")
        time.sleep(15)


if __name__ == "__main__":
    try:
        main()
    except subprocess.CalledProcessError as exc:
        detail = ((exc.stderr or exc.stdout) or str(exc)).strip()
        fail(detail)
    except SystemExit:
        raise
    except Exception as exc:  # pragma: no cover
        fail(str(exc))
        raise SystemExit(1) from exc
