#!/usr/bin/env python3
"""Install Firebase's exported Android registrations without inventing clients.

Pull requests build against the clearly local placeholder. Release builds use
--require-real and fail closed unless GOOGLE_SERVICES_JSON_BASE64 contains the
Firebase-exported apps for both production Android package IDs.
"""
from __future__ import annotations

import base64
import json
import os
import shutil
import stat
import subprocess
import sys
import tempfile
from pathlib import Path

def _die(message: str) -> None:
    print(f"::error::{message}", file=sys.stderr)
    raise SystemExit(1)

def _copy_placeholder(dest: Path, placeholder: Path) -> None:
    if not placeholder.is_file():
        _die("committed Firebase placeholder is missing")
    dest.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(placeholder, dest)
    os.chmod(dest, stat.S_IRUSR | stat.S_IWUSR)

def write_google_services(dest: Path, placeholder: Path, *,
                          require_real: bool = False,
                          use_placeholder: bool = False) -> bool:
    """Write config; return True only for the unmodified real secret."""
    raw = (os.environ.get("GOOGLE_SERVICES_JSON_BASE64") or "").strip()
    if use_placeholder:
        _copy_placeholder(dest, placeholder)
        print("Using the committed local Firebase placeholder for PR validation")
        return False
    if not raw:
        if require_real:
            _die("GOOGLE_SERVICES_JSON_BASE64 is required for release APK builds; "
                 "register in.gpstore.customer and in.gpstore.admin in Firebase "
                 "and store the exported JSON as this GitHub Actions secret")
        _copy_placeholder(dest, placeholder)
        print("GOOGLE_SERVICES_JSON_BASE64 unset; using local-only placeholder")
        return False
    try:
        blob = base64.b64decode("".join(raw.split()), validate=True)
    except (ValueError, base64.binascii.Error):
        _die("GOOGLE_SERVICES_JSON_BASE64 is not valid base64")
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_bytes(blob)
    os.chmod(dest, stat.S_IRUSR | stat.S_IWUSR)
    print("Decoded google-services.json from repo secret")
    return True

def _verify(path: Path) -> None:
    result = subprocess.run(
        [sys.executable, "tool/verify_google_services.py", str(path)],
        check=False,
    )
    if result.returncode:
        raise SystemExit(result.returncode)

def _self_test() -> None:
    import contextlib
    import io

    original = os.environ.pop("GOOGLE_SERVICES_JSON_BASE64", None)
    valid = {
        "project_info": {"project_number": "123456789", "project_id": "gpstore-test"},
        "client": [
            {"client_info": {"mobilesdk_app_id": "1:123456789:android:customer1",
                             "android_client_info": {"package_name": "in.gpstore.customer"}}},
            {"client_info": {"mobilesdk_app_id": "1:123456789:android:admin1",
                             "android_client_info": {"package_name": "in.gpstore.admin"}}},
        ],
    }
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        placeholder = root / "placeholder.json"
        dest = root / "google-services.json"
        placeholder.write_text('{"project_id":"gp-store-local"}', encoding="utf-8")
        assert write_google_services(dest, placeholder) is False
        assert dest.read_text(encoding="utf-8") == placeholder.read_text(encoding="utf-8")

        try:
            with contextlib.redirect_stderr(io.StringIO()):
                write_google_services(dest, placeholder, require_real=True)
        except SystemExit:
            pass
        else:
            raise AssertionError("--require-real accepted an absent Firebase secret")

        os.environ["GOOGLE_SERVICES_JSON_BASE64"] = base64.b64encode(
            json.dumps(valid).encode("utf-8")
        ).decode("ascii")
        assert write_google_services(dest, placeholder, require_real=True) is True
        _verify(dest)
        assert len(json.loads(dest.read_text(encoding="utf-8"))["client"]) == 2

        invalid = dict(valid)
        invalid["client"] = [valid["client"][0]]
        os.environ["GOOGLE_SERVICES_JSON_BASE64"] = base64.b64encode(
            json.dumps(invalid).encode("utf-8")
        ).decode("ascii")
        try:
            with contextlib.redirect_stderr(io.StringIO()):
                write_google_services(dest, placeholder, require_real=True)
                _verify(dest)
        except SystemExit:
            pass
        else:
            raise AssertionError("missing package registration was accepted")
    if original is not None:
        os.environ["GOOGLE_SERVICES_JSON_BASE64"] = original
    else:
        os.environ.pop("GOOGLE_SERVICES_JSON_BASE64", None)
    print("self-test ok")

def main() -> None:
    args = set(sys.argv[1:])
    if args == {"--self-test"}:
        _self_test()
        return
    allowed = {"--require-real", "--use-placeholder"}
    if args - allowed or len(args) > 1:
        _die("usage: decode_google_services.py [--require-real|--use-placeholder]")
    dest = Path("android/app/google-services.json")
    placeholder = Path("android/app/google-services.placeholder.json")
    require_real = "--require-real" in args
    use_placeholder = "--use-placeholder" in args
    if require_real and use_placeholder:
        _die("choose either --require-real or --use-placeholder")
    if write_google_services(dest, placeholder, require_real=require_real,
                             use_placeholder=use_placeholder):
        _verify(dest)

if __name__ == "__main__":
    main()
