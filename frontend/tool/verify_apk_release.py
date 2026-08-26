#!/usr/bin/env python3
"""Fail if a release APK is unsigned, not zip-aligned, or has the wrong package.

Prints the signer DN so CI logs distinguish Android Debug from a Play key.
Does not re-sign. Do not unzip/modify/sign APKs by hand.

Customer APKs must be com.gpstore.app. Worker APKs must be com.gpstore.worker.
Sharing one applicationId would make installing one replace the other.
"""
from __future__ import annotations

import glob
import os
import re
import subprocess
import sys


CUSTOMER_PACKAGE = "com.gpstore.app"
WORKER_PACKAGE = "com.gpstore.worker"

CUSTOMER_FORBIDDEN_PERMISSIONS = {
    "android.permission.CAMERA",
    "android.permission.ACCESS_BACKGROUND_LOCATION",
}
WORKER_FORBIDDEN_PERMISSIONS = {
    "android.permission.ACCESS_BACKGROUND_LOCATION",
    "android.permission.RECORD_AUDIO",
    "android.permission.BLUETOOTH_CONNECT",
    "android.permission.BLUETOOTH_SCAN",
}
WORKER_REQUIRED_PERMISSIONS = {
    "android.permission.CAMERA",
}


def permissions(aapt: str, apk: str) -> set[str]:
    result = subprocess.run(
        [aapt, "dump", "permissions", apk],
        capture_output=True,
        text=True,
    )
    blob = result.stdout or result.stderr
    found: set[str] = set()
    for line in blob.splitlines():
        match = re.search(r"name='([^']+)'", line)
        if match:
            found.add(match.group(1))
        else:
            stripped = line.strip()
            if stripped.startswith("android.permission."):
                found.add(stripped)
    return found


def permission_violations(package: str, perms: set[str]) -> list[str]:
    problems: list[str] = []
    if package == CUSTOMER_PACKAGE:
        extra = sorted(perms & CUSTOMER_FORBIDDEN_PERMISSIONS)
        if extra:
            problems.append(f"customer APK must not declare {extra}")
    if package == WORKER_PACKAGE:
        extra = sorted(perms & WORKER_FORBIDDEN_PERMISSIONS)
        if extra:
            problems.append(f"worker APK must not declare {extra}")
        missing = sorted(WORKER_REQUIRED_PERMISSIONS - perms)
        if missing:
            problems.append(f"worker APK must declare {missing}")
    return problems


def sdk_tool(name: str) -> str:
    sdk = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
    if not sdk:
        sys.exit("ANDROID_SDK_ROOT / ANDROID_HOME is not set")
    matches = sorted(glob.glob(os.path.join(sdk, "build-tools", "*", name)))
    if not matches:
        sys.exit(f"no {name} under {sdk}/build-tools")
    return matches[-1]


def expected_package(apk: str) -> str | None:
    name = os.path.basename(apk).lower()
    if "worker" in name:
        return WORKER_PACKAGE
    if name.startswith("app-") and name.endswith("-release.apk"):
        return CUSTOMER_PACKAGE
    return None


def package_name(aapt: str, apk: str) -> str:
    result = subprocess.run(
        [aapt, "dump", "badging", apk],
        capture_output=True,
        text=True,
    )
    blob = result.stdout or result.stderr
    match = re.search(r"package: name='([^']+)'", blob)
    if result.returncode != 0 or not match:
        raise RuntimeError(f"aapt dump badging failed for {apk}: {blob.strip()}")
    return match.group(1)


def main() -> None:
    if len(sys.argv) < 2:
        print("usage: verify_apk_release.py <apk>...", file=sys.stderr)
        sys.exit(2)
    apksigner = sdk_tool("apksigner")
    zipalign = sdk_tool("zipalign")
    aapt = sdk_tool("aapt")
    failed = False
    seen: dict[str, str] = {}
    for apk in sys.argv[1:]:
        if not os.path.isfile(apk):
            print(f"MISSING {apk}")
            failed = True
            continue
        v = subprocess.run(
            [apksigner, "verify", "--verbose", "--print-certs", apk],
            capture_output=True,
            text=True,
        )
        print(f"=== apksigner {os.path.basename(apk)} ===")
        print(v.stdout or v.stderr)
        if v.returncode != 0:
            print(f"UNSIGNED_OR_INVALID {apk}")
            failed = True
        out = v.stdout or ""
        v1 = "Verified using v1 scheme (JAR signing): true" in out
        v2 = "Verified using v2 scheme (APK Signature Scheme v2): true" in out
        v3 = "Verified using v3 scheme (APK Signature Scheme v3): true" in out
        print(f"SCHEMES v1={v1} v2={v2} v3={v3}")
        if v.returncode == 0 and not v1:
            print(
                "NOTE: missing META-INF/*.RSA is v1 JAR signing. "
                "Play accepts v2/v3. apksigner is the check, not unzipping META-INF."
            )
        debug = "CN=Android Debug" in out
        if debug:
            print(
                "SIGNER=Android Debug — sideload/CI only. "
                "Play upload needs android/key.properties from a real keystore "
                "(see android/key.properties.example). Do not sign a modified APK by hand."
            )
            if os.environ.get("REQUIRE_RELEASE_SIGNING") == "1":
                print(f"DEBUG_SIGNER_NOT_ALLOWED {apk}")
                failed = True
        else:
            print("SIGNER=release (not Android Debug)")
        try:
            pkg = package_name(aapt, apk)
        except RuntimeError as ex:
            print(ex)
            failed = True
            pkg = ""
        print(f"PACKAGE {os.path.basename(apk)}={pkg}")
        expected = expected_package(apk)
        if expected and pkg != expected:
            print(f"WRONG_PACKAGE {apk}: got {pkg!r}, expected {expected!r}")
            failed = True
        if pkg:
            seen[os.path.basename(apk)] = pkg
            perms = permissions(aapt, apk)
            print(f"PERMISSIONS {os.path.basename(apk)}={sorted(perms)}")
            for problem in permission_violations(pkg, perms):
                print(f"BAD_PERMISSION {apk}: {problem}")
                failed = True
        z = subprocess.run(
            [zipalign, "-c", "-P", "16", "4", apk],
            capture_output=True,
            text=True,
        )
        if z.returncode != 0:
            print(f"NOT_ZIPALIGNED {apk}")
            print(z.stdout or z.stderr)
            failed = True
        else:
            print(f"ZIPALIGN_OK {os.path.basename(apk)} (16 KiB page / 4-byte)")

    worker_pkgs = {pkg for name, pkg in seen.items() if "worker" in name.lower()}
    customer_pkgs = {
        pkg for name, pkg in seen.items()
        if name.startswith("app-") and name.endswith("-release.apk")
    }
    if worker_pkgs and customer_pkgs and worker_pkgs & customer_pkgs:
        print(
            "SHARED_APPLICATION_ID worker and customer APKs must not use "
            f"the same package: {sorted(worker_pkgs & customer_pkgs)}"
        )
        failed = True
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    if len(sys.argv) == 2 and sys.argv[1] == "--self-test":
        assert permission_violations(
            CUSTOMER_PACKAGE, {"android.permission.CAMERA"}
        ), "customer CAMERA must fail"
        assert not permission_violations(
            CUSTOMER_PACKAGE, {"android.permission.INTERNET"}
        )
        assert permission_violations(
            WORKER_PACKAGE, {"android.permission.INTERNET"}
        ), "worker missing CAMERA must fail"
        assert not permission_violations(
            WORKER_PACKAGE,
            {"android.permission.CAMERA", "android.permission.ACCESS_FINE_LOCATION"},
        )
        assert permission_violations(
            WORKER_PACKAGE,
            {"android.permission.CAMERA", "android.permission.RECORD_AUDIO"},
        )
        print("self-test ok")
        sys.exit(0)
    main()
