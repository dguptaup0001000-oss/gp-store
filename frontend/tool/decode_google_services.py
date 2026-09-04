#!/usr/bin/env python3
"""Decode GOOGLE_SERVICES_JSON_BASE64 or copy the committed placeholder.

Does not print secret values. Play Console is not required. A missing
Firebase secret yields the placeholder so release-signed APKs still build;
push/Crashlytics stay off until a real google-services.json is stored.

THE SECRET IS CHECKED EXACTLY AS IT ARRIVED, and that is the whole point of
this file.

WHAT THIS REPLACED, because it shipped and nobody could see it. When the real
secret did not list a flavor's package_name, this script used to CLONE an
existing client, rewrite package_name to match, and synthesise a
mobilesdk_app_id by replacing the last segment of a real App ID with the
literal string "customer" or "admin":

    1:123456789012:android:a1b2c3d4e5f6   ->   1:123456789012:android:customer

No Firebase project contains that App ID. Firebase.initializeApp() still
succeeded, because it only reads this local file - so the app reported itself
healthy and bootstrap.dart never hit its "Firebase not configured" branch -
while FCM registration was refused by Google. No token, no push, and
Crashlytics reports keyed to an app that does not exist. Every order
notification, delivery update and admin alert was silently dropped.

AND THE GUARD COULD NOT CATCH IT. verify_google_services.py ran AFTER the
cloning, and all it checks is that in.gpstore.customer and in.gpstore.admin
are present - which they were, because the cloning had just invented them. It
printed "Decoded real google-services.json" over a forgery this script had
made one line earlier. A check that runs after the thing it is checking for
has been manufactured is not a check.

So the cloning is gone rather than reordered. A real secret that does not
list every flavor now fails the build, and the fix is to register those
Android apps in Firebase and update GOOGLE_SERVICES_JSON_BASE64 - never to
manufacture an identity for them. Making the build red is the correct
outcome: the APK it would otherwise produce cannot receive a push.
"""
from __future__ import annotations

import base64
import json
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


def verify_secret(dest: Path, quiet: bool = False) -> int:
    """Validate the decoded secret. Returns the checker's exit code.

    Resolved from this file rather than the working directory so the two
    scripts cannot drift apart depending on where CI happens to cd to.
    """
    checker = Path(__file__).resolve().with_name("verify_google_services.py")
    return subprocess.run(
        [sys.executable, str(checker), str(dest)],
        capture_output=quiet,
        text=True,
    ).returncode


def run(dest: Path, placeholder: Path) -> int:
    """The whole production path, so a test can exercise it end to end.

    Deliberately tiny and deliberately a function: the defect this file exists
    to prevent was an extra step wedged between decoding and verifying, and a
    main() that nothing can call is a main() nothing can test.
    """
    if write_google_services(dest, placeholder):
        return verify_secret(dest)
    return 0


def _self_test() -> None:
    import tempfile

    os.environ.pop("GOOGLE_SERVICES_JSON_BASE64", None)
    with tempfile.TemporaryDirectory() as tmp:
        placeholder = Path(tmp) / "placeholder.json"
        dest = Path(tmp) / "google-services.json"
        placeholder.write_text("{}", encoding="utf-8")
        assert write_google_services(dest, placeholder) is False
        assert dest.read_text(encoding="utf-8") == "{}"

    def payload(packages: list[str]) -> dict:
        return {
            "project_id": "unit-test",
            "client": [
                {
                    "client_info": {
                        "mobilesdk_app_id": f"1:9:android:a1b2c3d4e5f{index}",
                        "android_client_info": {"package_name": pkg},
                    },
                    "api_key": [{"current_key": "not-a-real-key"}],
                }
                for index, pkg in enumerate(packages)
            ],
        }

    def decode_into(dest: Path, data: dict) -> None:
        os.environ["GOOGLE_SERVICES_JSON_BASE64"] = base64.b64encode(
            json.dumps(data).encode("utf-8")
        ).decode("ascii")
        placeholder = dest.with_name("placeholder.json")
        placeholder.write_text("nope", encoding="utf-8")
        assert write_google_services(dest, placeholder) is True

    # THE EXACT SHAPE THAT SHIPPED. The real project had the legacy package
    # registered and neither flavor, and the build passed anyway.
    with tempfile.TemporaryDirectory() as tmp:
        dest = Path(tmp) / "google-services.json"
        decode_into(dest, payload(["com.gpstore.app"]))
        assert verify_secret(dest, quiet=True) != 0, (
            "a secret listing neither flavor was accepted - push would be dead "
            "in the shipped APK and the build would not say so"
        )

    # One flavor is not enough either: the admin APK would be the broken one.
    with tempfile.TemporaryDirectory() as tmp:
        dest = Path(tmp) / "google-services.json"
        decode_into(dest, payload(["com.gpstore.app", "in.gpstore.customer"]))
        assert verify_secret(dest, quiet=True) != 0, (
            "a secret missing in.gpstore.admin was accepted"
        )

    # A COMPLETE SECRET STILL PASSES. Without this the guard could be
    # satisfied by rejecting everything, which fixes nothing.
    with tempfile.TemporaryDirectory() as tmp:
        dest = Path(tmp) / "google-services.json"
        decode_into(
            dest,
            payload(["com.gpstore.app", "in.gpstore.customer", "in.gpstore.admin"]),
        )
        assert verify_secret(dest) == 0, "a complete secret was rejected"
        data = json.loads(dest.read_text(encoding="utf-8"))
        # NOTHING WAS ADDED. The file must reach Gradle exactly as stored.
        assert len(data["client"]) == 3, "the decoded secret was modified"
        for client in data["client"]:
            app_id = client["client_info"]["mobilesdk_app_id"]
            assert not app_id.endswith(("customer", "admin")), (
                f"a flavor App ID was synthesised: {app_id}"
            )

    # THE FORGERY ITSELF, presented as a complete secret. Every required
    # package is listed, so the package check passes; only the App ID gives it
    # away. This is precisely what the old cloning produced and what the build
    # used to accept.
    with tempfile.TemporaryDirectory() as tmp:
        dest = Path(tmp) / "google-services.json"
        forged = payload(
            ["com.gpstore.app", "in.gpstore.customer", "in.gpstore.admin"]
        )
        forged["client"][1]["client_info"]["mobilesdk_app_id"] = (
            "1:9:android:customer"
        )
        decode_into(dest, forged)
        assert verify_secret(dest, quiet=True) != 0, (
            "a synthesised flavor App ID was accepted - the exact bug that "
            "shipped push-dead APKs while the build reported success"
        )

    # END TO END, through run() rather than the checker alone. The bug was
    # never in the checker - it was a cloning step wedged between decoding and
    # checking, so a test that calls the checker directly would have passed
    # throughout the entire time push was broken. This one would not have.
    with tempfile.TemporaryDirectory() as tmp:
        dest = Path(tmp) / "google-services.json"
        placeholder = dest.with_name("placeholder.json")
        placeholder.write_text("nope", encoding="utf-8")
        os.environ["GOOGLE_SERVICES_JSON_BASE64"] = base64.b64encode(
            json.dumps(payload(["com.gpstore.app"])).encode("utf-8")
        ).decode("ascii")
        assert run(dest, placeholder) != 0, (
            "the real decode path accepted a secret with neither flavor "
            "registered - something is repairing the secret before it is checked"
        )

    # And the placeholder path still succeeds, so PR builds keep working.
    with tempfile.TemporaryDirectory() as tmp:
        os.environ.pop("GOOGLE_SERVICES_JSON_BASE64", None)
        dest = Path(tmp) / "google-services.json"
        placeholder = dest.with_name("placeholder.json")
        placeholder.write_text("{}", encoding="utf-8")
        assert run(dest, placeholder) == 0, "the placeholder path was broken"

    os.environ.pop("GOOGLE_SERVICES_JSON_BASE64", None)
    print("self-test ok")


if __name__ == "__main__":
    if len(sys.argv) == 2 and sys.argv[1] == "--self-test":
        _self_test()
        raise SystemExit(0)
    raise SystemExit(
        run(
            Path("android/app/google-services.json"),
            Path("android/app/google-services.placeholder.json"),
        )
    )
