#!/usr/bin/env python3
"""Fail if a release APK is unsigned or not zip-aligned.

Prints the signer DN so CI logs distinguish Android Debug from a Play key.
Does not re-sign. Do not unzip/modify/sign APKs by hand.
"""
from __future__ import annotations

import glob
import os
import subprocess
import sys


def sdk_tool(name: str) -> str:
    sdk = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
    if not sdk:
        sys.exit("ANDROID_SDK_ROOT / ANDROID_HOME is not set")
    matches = sorted(glob.glob(os.path.join(sdk, "build-tools", "*", name)))
    if not matches:
        sys.exit(f"no {name} under {sdk}/build-tools")
    return matches[-1]


def main() -> None:
    if len(sys.argv) < 2:
        print("usage: verify_apk_release.py <apk>...", file=sys.stderr)
        sys.exit(2)
    apksigner = sdk_tool("apksigner")
    zipalign = sdk_tool("zipalign")
    failed = False
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
        else:
            print("SIGNER=release (not Android Debug)")
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
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
