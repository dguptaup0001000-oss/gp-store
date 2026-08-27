#!/usr/bin/env python3
"""Decode GOOGLE_SERVICES_JSON_BASE64 or copy the committed placeholder.

Does not print secret values. Play Console is not required. A missing
Firebase secret yields the placeholder so release-signed APKs still build;
push/Crashlytics stay off until a real google-services.json is stored.
"""
from __future__ import annotations

import base64
import os
import shutil
import stat
import subprocess
import sys
from pathlib import Path


def _die(message: str) -> None:
    prefix = "::error::" if "--self-test" not in sys.argv else "expected failure: "
    print(f"{prefix}{message}", file=sys.stderr)
    raise SystemExit(1)


def write_google_services(dest: Path, placeholder: Path) -> bool:
    """Return True when a repo secret was decoded (caller should verify)."""
    raw = (os.environ.get("GOOGLE_SERVICES_JSON_BASE64") or "").strip()
    if not raw:
        print(
            "GOOGLE_SERVICES_JSON_BASE64 unset; using the committed placeholder "
            "(push/Crashlytics will not work). Release signing does not require "
            "Play Console or this Firebase secret."
        )
        shutil.copyfile(placeholder, dest)
        return False
    try:
        blob = base64.b64decode("".join(raw.split()), validate=True)
    except ValueError:
        _die("GOOGLE_SERVICES_JSON_BASE64 is not valid base64")
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_bytes(blob)
    os.chmod(dest, stat.S_IRUSR | stat.S_IWUSR)
    print("Decoded google-services.json from repo secret")
    return True


def _self_test() -> None:
    import json
    import tempfile

    os.environ.pop("GOOGLE_SERVICES_JSON_BASE64", None)
    with tempfile.TemporaryDirectory() as tmp:
        placeholder = Path(tmp) / "placeholder.json"
        dest = Path(tmp) / "google-services.json"
        placeholder.write_text("{}", encoding="utf-8")
        assert write_google_services(dest, placeholder) is False
        assert dest.read_text(encoding="utf-8") == "{}"
    payload = {"project_id": "unit-test"}
    os.environ["GOOGLE_SERVICES_JSON_BASE64"] = base64.b64encode(
        json.dumps(payload).encode("utf-8")
    ).decode("ascii")
    with tempfile.TemporaryDirectory() as tmp:
        dest = Path(tmp) / "google-services.json"
        placeholder = Path(tmp) / "placeholder.json"
        placeholder.write_text("nope", encoding="utf-8")
        assert write_google_services(dest, placeholder) is True
        assert json.loads(dest.read_text(encoding="utf-8"))["project_id"] == "unit-test"
    print("self-test ok")


if __name__ == "__main__":
    if len(sys.argv) == 2 and sys.argv[1] == "--self-test":
        _self_test()
        raise SystemExit(0)
    dest = Path("android/app/google-services.json")
    placeholder = Path("android/app/google-services.placeholder.json")
    if write_google_services(dest, placeholder):
        raise SystemExit(
            subprocess.call(
                [sys.executable, "tool/verify_google_services.py", str(dest)]
            )
        )
