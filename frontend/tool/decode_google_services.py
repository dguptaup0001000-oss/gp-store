#!/usr/bin/env python3
"""Decode GOOGLE_SERVICES_JSON_BASE64 or copy the committed placeholder.

Does not print secret values. Play Console is not required. A missing
Firebase secret yields the placeholder so release-signed APKs still build;
push/Crashlytics stay off until a real google-services.json is stored.

A real secret that still only lists com.gpstore.app is not enough for the
customer/admin flavors. ensure_flavor_clients() clones that existing client
for in.gpstore.customer and in.gpstore.admin so the google-services plugin
can match applicationId. Register those Android apps in Firebase and update
the secret so the cloned mobilesdk_app_id is replaced with a real one.
"""
from __future__ import annotations

import base64
import copy
import json
import os
import shutil
import stat
import subprocess
import sys
from pathlib import Path

REQUIRED_PACKAGES = ("in.gpstore.customer", "in.gpstore.admin")


def _die(message: str) -> None:
    prefix = "::error::" if "--self-test" not in sys.argv else "expected failure: "
    print(f"{prefix}{message}", file=sys.stderr)
    raise SystemExit(1)


def _package_name(client: dict) -> str:
    info = (client.get("client_info") or {}).get("android_client_info") or {}
    return str(info.get("package_name") or "")


def ensure_flavor_clients(dest: Path) -> list[str]:
    """Add cloned clients for missing flavor package names. Returns added pkgs."""
    data = json.loads(dest.read_text(encoding="utf-8"))
    clients = list(data.get("client") or [])
    present = {_package_name(client) for client in clients}
    template = next((client for client in clients if _package_name(client)), None)
    added: list[str] = []
    if template is None:
        dest.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")
        return added
    for pkg in REQUIRED_PACKAGES:
        if pkg in present:
            continue
        clone = copy.deepcopy(template)
        info = clone.setdefault("client_info", {})
        android = info.setdefault("android_client_info", {})
        android["package_name"] = pkg
        app_id = str(info.get("mobilesdk_app_id") or "1:1:android:clone")
        suffix = pkg.rsplit(".", 1)[-1]
        if ":" in app_id:
            info["mobilesdk_app_id"] = ":".join(app_id.split(":")[:-1] + [suffix])
        else:
            info["mobilesdk_app_id"] = f"{app_id}:{suffix}"
        clients.append(clone)
        added.append(pkg)
    data["client"] = clients
    dest.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")
    os.chmod(dest, stat.S_IRUSR | stat.S_IWUSR)
    if added:
        print(
            "google-services.json was missing flavor package_name "
            f"{', '.join(added)}; cloned the existing Firebase client so "
            "Gradle can match applicationId. Register those Android apps in "
            "Firebase and update GOOGLE_SERVICES_JSON_BASE64."
        )
    return added


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
    import tempfile

    os.environ.pop("GOOGLE_SERVICES_JSON_BASE64", None)
    with tempfile.TemporaryDirectory() as tmp:
        placeholder = Path(tmp) / "placeholder.json"
        dest = Path(tmp) / "google-services.json"
        placeholder.write_text("{}", encoding="utf-8")
        assert write_google_services(dest, placeholder) is False
        assert dest.read_text(encoding="utf-8") == "{}"
    payload = {
        "project_id": "unit-test",
        "client": [
            {
                "client_info": {
                    "mobilesdk_app_id": "1:9:android:legacy",
                    "android_client_info": {"package_name": "com.gpstore.app"},
                },
                "api_key": [{"current_key": "not-a-real-key"}],
            }
        ],
    }
    os.environ["GOOGLE_SERVICES_JSON_BASE64"] = base64.b64encode(
        json.dumps(payload).encode("utf-8")
    ).decode("ascii")
    with tempfile.TemporaryDirectory() as tmp:
        dest = Path(tmp) / "google-services.json"
        placeholder = Path(tmp) / "placeholder.json"
        placeholder.write_text("nope", encoding="utf-8")
        assert write_google_services(dest, placeholder) is True
        added = ensure_flavor_clients(dest)
        data = json.loads(dest.read_text(encoding="utf-8"))
        pkgs = {
            ((c.get("client_info") or {}).get("android_client_info") or {}).get(
                "package_name"
            )
            for c in data["client"]
        }
        assert set(added) == {"in.gpstore.customer", "in.gpstore.admin"}
        assert pkgs == {
            "com.gpstore.app",
            "in.gpstore.customer",
            "in.gpstore.admin",
        }
        assert data["project_id"] == "unit-test"
        assert ensure_flavor_clients(dest) == []
    print("self-test ok")


if __name__ == "__main__":
    if len(sys.argv) == 2 and sys.argv[1] == "--self-test":
        _self_test()
        raise SystemExit(0)
    dest = Path("android/app/google-services.json")
    placeholder = Path("android/app/google-services.placeholder.json")
    if write_google_services(dest, placeholder):
        ensure_flavor_clients(dest)
        raise SystemExit(
            subprocess.call(
                [sys.executable, "tool/verify_google_services.py", str(dest)]
            )
        )
