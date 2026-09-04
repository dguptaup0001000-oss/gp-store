#!/usr/bin/env python3
"""Fail if google-services.json is missing, invalid, or the local placeholder.

Does not print API keys. Production APKs must ship Firebase Android apps
for in.gpstore.customer and in.gpstore.admin. The committed placeholder
is only for PR builds.
"""
from __future__ import annotations

import json
import sys

PLACEHOLDER_PROJECT = "gp-store-local"
PLACEHOLDER_KEY = "local-placeholder-not-a-secret"
REQUIRED_PACKAGES = ("in.gpstore.customer", "in.gpstore.admin")


def main() -> None:
    if len(sys.argv) != 2:
        print("usage: verify_google_services.py <google-services.json>", file=sys.stderr)
        sys.exit(2)
    path = sys.argv[1]
    try:
        with open(path, encoding="utf-8") as handle:
            data = json.load(handle)
    except (OSError, json.JSONDecodeError) as exc:
        sys.exit(f"google-services.json is not valid JSON: {exc}")

    if data.get("project_id") == PLACEHOLDER_PROJECT:
        sys.exit(
            "GOOGLE_SERVICES_JSON_BASE64 is the committed placeholder, "
            "not a real Firebase project"
        )

    clients = data.get("client") or []
    if not clients:
        sys.exit("google-services.json has no client entries")

    pkgs: list[str] = []
    for client in clients:
        info = (client.get("client_info") or {}).get("android_client_info") or {}
        pkg = info.get("package_name")
        if pkg:
            pkgs.append(pkg)
        for key in client.get("api_key") or []:
            if key.get("current_key") == PLACEHOLDER_KEY:
                sys.exit("GOOGLE_SERVICES_JSON_BASE64 contains the placeholder API key")

    missing = [pkg for pkg in REQUIRED_PACKAGES if pkg not in pkgs]
    if missing:
        shown = ",".join(pkgs) if pkgs else "(none)"
        sys.exit(
            "google-services.json must include package_name "
            f"{', '.join(REQUIRED_PACKAGES)} (missing {', '.join(missing)}), "
            f"got: {shown}"
        )
    print(
        "Decoded real google-services.json for "
        + ", ".join(REQUIRED_PACKAGES)
    )


if __name__ == "__main__":
    main()
