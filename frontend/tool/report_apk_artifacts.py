#!/usr/bin/env python3
"""Print actual APK sizes and SHA-256. GitHub artifact zip size is not the APK size."""

from __future__ import annotations

import hashlib
import os
import pathlib
import sys

ROWS = (
    ("Customer", "gpstore-customer-release.apk", "ARM64 / arm64-v8a"),
    ("Customer", "gpstore-customer-armv7.apk", "ARMv7 / armeabi-v7a"),
    ("Admin", "gpstore-admin-release.apk", "ARM64 / arm64-v8a"),
    ("Admin", "gpstore-admin-armv7.apk", "ARMv7 / armeabi-v7a"),
    ("Worker", "gpstore-worker-arm64.apk", "ARM64 / arm64-v8a"),
    ("Worker", "gpstore-worker-armv7.apk", "ARMv7 / armeabi-v7a"),
)


def main() -> int:
    if len(sys.argv) == 2 and sys.argv[1] == "--self-test":
        import tempfile

        with tempfile.TemporaryDirectory() as tmp:
            d = pathlib.Path(tmp)
            for _, name, _ in ROWS:
                (d / name).write_bytes(b"apk-bytes")
            os.environ.pop("GITHUB_STEP_SUMMARY", None)
            rc = 0
            sys.argv = ["report_apk_artifacts.py", str(d), "deadbeef" * 5, "deadbee", "1.0.0+2"]
            try:
                rc = main()
            finally:
                sys.argv = ["report_apk_artifacts.py", "--self-test"]
            sha_files = list(d.glob("*.apk.sha256"))
            if rc != 0 or len(sha_files) != 6:
                print("self-test failed", rc, len(sha_files), file=sys.stderr)
                return 1
            print("report_apk_artifacts.py self-test ok")
            return 0
    if len(sys.argv) != 5:
        print("usage: report_apk_artifacts.py <apk-dir> <git-sha> <short-sha> <version>", file=sys.stderr)
        return 2
    out = pathlib.Path(sys.argv[1])
    git_sha, short, version = sys.argv[2], sys.argv[3], sys.argv[4]
    lines = [
        "### APK artifacts (actual file size, not GitHub zip size)",
        "",
        "GitHub Actions compresses each artifact for the UI. That compressed number is **not** the APK size.",
        "",
        f"Git SHA `{git_sha}` (`{short}`). App version `{version}`. API `https://api.gpstore.co.in/v1`. `APP_ENV=production`.",
        "",
        "| App | File | ABI | Actual bytes | Actual MiB | SHA-256 |",
        "|---|---|---|---:|---:|---|",
    ]
    missing: list[str] = []
    for app, name, abi in ROWS:
        path = out / name
        if not path.is_file() or path.stat().st_size == 0:
            missing.append(name)
            continue
        data = path.read_bytes()
        digest = hashlib.sha256(data).hexdigest()
        mib = len(data) / (1024 * 1024)
        lines.append(f"| {app} | `{name}` | {abi} | {len(data)} | {mib:.2f} | `{digest}` |")
        print(f"{name} abi={abi} bytes={len(data)} mib={mib:.2f} sha256={digest}")
        path.with_suffix(path.suffix + ".sha256").write_text(f"{digest}  {name}\n")
    lines.extend(
        [
            "",
            "Do not produce a combined customer+admin production APK.",
        ]
    )
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    text = "\n".join(lines) + "\n"
    if summary:
        with open(summary, "a", encoding="utf-8") as fh:
            fh.write(text)
    else:
        sys.stdout.write(text)
    if missing:
        print("missing APKs: " + ", ".join(missing), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
