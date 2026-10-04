#!/usr/bin/env python3
"""Validate Firebase-exported Android apps needed by production flavors."""
from __future__ import annotations

import copy
import json
import re
import sys
from pathlib import Path

PLACEHOLDER_PROJECT = "gp-store-local"
REQUIRED_PACKAGES = ("in.gpstore.customer", "in.gpstore.admin")
APP_ID = re.compile(r"^1:(\d+):android:([A-Za-z0-9_-]+)$")
FABRICATED_SUFFIXES = {"customer", "admin", "clone", "placeholder"}

def validate(data: object) -> None:
    if not isinstance(data, dict):
        raise ValueError("google-services.json root must be an object")
    project = data.get("project_info") or {}
    project_id = project.get("project_id")
    project_number = str(project.get("project_number") or "")
    if not project_id or project_id == PLACEHOLDER_PROJECT:
        raise ValueError("google-services.json must come from a real Firebase project")
    if not project_number.isdigit():
        raise ValueError("Firebase project_info.project_number must be numeric")
    clients = data.get("client")
    if not isinstance(clients, list) or not clients:
        raise ValueError("google-services.json has no client entries")

    by_package: dict[str, list[dict]] = {}
    for client in clients:
        if not isinstance(client, dict):
            continue
        info = client.get("client_info") or {}
        android = info.get("android_client_info") or {}
        package = android.get("package_name")
        if package:
            by_package.setdefault(str(package), []).append(client)

    for package in REQUIRED_PACKAGES:
        matches = by_package.get(package, [])
        if len(matches) != 1:
            raise ValueError(
                f"Firebase export must contain exactly one Android app for {package}; "
                f"found {len(matches)}"
            )
        info = matches[0].get("client_info") or {}
        app_id = str(info.get("mobilesdk_app_id") or "")
        match = APP_ID.fullmatch(app_id)
        if not match:
            raise ValueError(
                f"Firebase export has a missing or malformed mobilesdk_app_id for {package}"
            )
        if match.group(1) != project_number:
            raise ValueError(
                f"Firebase app ID project number does not match project_info for {package}"
            )
        if match.group(2).lower() in FABRICATED_SUFFIXES:
            raise ValueError(
                f"Firebase App ID for {package} matches a known fabricated placeholder"
            )
    app_ids = [
        str((matches[0].get("client_info") or {}).get("mobilesdk_app_id") or "")
        for package, matches in by_package.items()
        if package in REQUIRED_PACKAGES and matches
    ]
    if len(app_ids) != len(REQUIRED_PACKAGES) or len(set(app_ids)) != len(app_ids):
        raise ValueError("Customer and Admin Firebase app IDs must be distinct")

def main() -> None:
    if len(sys.argv) == 2 and sys.argv[1] == "--self-test":
        valid = {
            "project_info": {"project_number": "123456789", "project_id": "gpstore-test"},
            "client": [
                {"client_info": {"mobilesdk_app_id": "1:123456789:android:customer1",
                                 "android_client_info": {"package_name": "in.gpstore.customer"}}},
                {"client_info": {"mobilesdk_app_id": "1:123456789:android:admin1",
                                 "android_client_info": {"package_name": "in.gpstore.admin"}}},
            ],
        }
        validate(valid)
        malformed = copy.deepcopy(valid)
        malformed["client"][0]["client_info"]["mobilesdk_app_id"] = (
            "1:123456789:android:customer"
        )
        mismatched = copy.deepcopy(valid)
        mismatched["client"][0]["client_info"]["mobilesdk_app_id"] = (
            "1:987654321:android:customer1"
        )
        duplicated = copy.deepcopy(valid)
        duplicated["client"][1]["client_info"]["mobilesdk_app_id"] = (
            duplicated["client"][0]["client_info"]["mobilesdk_app_id"]
        )
        for mutation in (
            {"project_info": {"project_number": "1", "project_id": PLACEHOLDER_PROJECT},
             "client": valid["client"]},
            {"project_info": valid["project_info"], "client": valid["client"][:1]},
            malformed,
            mismatched,
            duplicated,
        ):
            try:
                validate(mutation)
            except ValueError:
                continue
            raise SystemExit("self-test failed: invalid Firebase config accepted")
        print("self-test ok")
        return
    if len(sys.argv) != 2:
        raise SystemExit("usage: verify_google_services.py <google-services.json>")
    try:
        data = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
        validate(data)
    except (OSError, json.JSONDecodeError, ValueError) as exc:
        raise SystemExit(f"google-services.json validation failed: {exc}")
    print("Verified distinct Firebase Android app IDs for "
          + ", ".join(REQUIRED_PACKAGES))

if __name__ == "__main__":
    main()
