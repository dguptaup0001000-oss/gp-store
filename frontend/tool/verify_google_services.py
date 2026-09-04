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

# A real mobilesdk_app_id ends in a hex identifier Firebase issued
# (1:123456789012:android:a1b2c3d4e5f6). These endings cannot occur in one.
#
# They are what decode_google_services.py used to WRITE: when the secret was
# missing a flavor it cloned another client and replaced that last segment
# with the flavor name, producing an App ID no Firebase project contains.
# Firebase.initializeApp() accepted it, FCM did not, and push was dead in a
# green build. The cloning is gone; this is here so it cannot come back by
# another route - a hand-edited secret, or a future "helpful" fixup.
FORGED_APP_ID_ENDINGS = ("customer", "admin", "clone")


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
        client_info = client.get("client_info") or {}
        info = client_info.get("android_client_info") or {}
        pkg = info.get("package_name")
        if pkg:
            pkgs.append(pkg)
        app_id = str(client_info.get("mobilesdk_app_id") or "")
        if app_id.rsplit(":", 1)[-1] in FORGED_APP_ID_ENDINGS:
            sys.exit(
                f"mobilesdk_app_id for {pkg or '(unknown package)'} ends in "
                f"'{app_id.rsplit(':', 1)[-1]}', which Firebase never issues. "
                "This App ID was synthesised, not registered - push and "
                "Crashlytics cannot work for it. Register the app in Firebase "
                "and update GOOGLE_SERVICES_JSON_BASE64."
            )
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
