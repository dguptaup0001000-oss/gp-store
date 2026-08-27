#!/usr/bin/env python3
"""Choose the production API URL for APK and web CI.

GitHub `vars.API_BASE_URL` used to point at Render (`gp-store.onrender.com`).
That host is gone, so every login from those APKs is HTTP 404 HTML/text and
the app shows "Request failed (HTTP 404)". An unset var is fine; a stale
Render (or localhost) value must not win over the Hostinger URL.
"""

from __future__ import annotations

import argparse
import os
import sys

CANONICAL = "https://api.gpstore.co.in/v1"
RETIRED_MARKERS = (
    "onrender.com",
    "localhost",
    "127.0.0.1",
    "10.0.2.2",
    "railway.app",
)


def resolve(candidate: str | None) -> tuple[str, str | None]:
    raw = (candidate or "").strip()
    if raw.endswith("/"):
        raw = raw[:-1]
    if not raw:
        return CANONICAL, f"vars.API_BASE_URL is unset; using {CANONICAL}"
    lowered = raw.lower()
    for marker in RETIRED_MARKERS:
        if marker in lowered:
            return (
                CANONICAL,
                f"refusing API_BASE_URL {raw!r} (retired/non-production host); using {CANONICAL}",
            )
    if not lowered.startswith("https://"):
        return CANONICAL, f"refusing non-https API_BASE_URL {raw!r}; using {CANONICAL}"
    if not lowered.endswith("/v1"):
        return (
            CANONICAL,
            f"refusing API_BASE_URL {raw!r} (must end with /v1); using {CANONICAL}",
        )
    if "api.gpstore.co.in" not in lowered:
        return (
            CANONICAL,
            f"refusing API_BASE_URL {raw!r} (not api.gpstore.co.in); using {CANONICAL}",
        )
    return raw, None


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--candidate", default=None)
    parser.add_argument(
        "--github-env",
        action="store_true",
        help="Write API_BASE_URL=... to $GITHUB_ENV",
    )
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        cases = (
            (None, CANONICAL),
            ("", CANONICAL),
            ("https://gp-store.onrender.com/v1", CANONICAL),
            ("https://gp-store.onrender.com/v1/", CANONICAL),
            ("https://api.gpstore.co.in", CANONICAL),
            ("http://api.gpstore.co.in/v1", CANONICAL),
            ("https://evil.example/v1", CANONICAL),
            (CANONICAL, CANONICAL),
            (CANONICAL + "/", CANONICAL),
        )
        for candidate, expected in cases:
            got, _ = resolve(candidate)
            if got != expected:
                print(f"self-test failed: {candidate!r} -> {got!r}, want {expected!r}", file=sys.stderr)
                return 1
        print("resolve_api_base_url.py self-test ok")
        return 0
    candidate = args.candidate
    if candidate is None:
        candidate = os.environ.get("CANDIDATE_API_BASE_URL", "")
    url, warning = resolve(candidate)
    if warning:
        print(f"::warning::{warning}")
    if args.github_env:
        env_path = os.environ.get("GITHUB_ENV")
        if not env_path:
            print("GITHUB_ENV is not set", file=sys.stderr)
            return 2
        with open(env_path, "a", encoding="utf-8") as fh:
            fh.write(f"API_BASE_URL={url}\n")
        summary = os.environ.get("GITHUB_STEP_SUMMARY")
        if summary:
            with open(summary, "a", encoding="utf-8") as fh:
                fh.write("### Production API URL\n\n")
                fh.write(f"`{url}`\n\n")
                if warning:
                    fh.write(f"{warning}\n")
    print(url)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
