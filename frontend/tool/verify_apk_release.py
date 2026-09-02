#!/usr/bin/env python3
"""Fail if a release APK is unsigned, not zip-aligned, 4 KiB ELF-aligned, or has the wrong package.

Prints the signer DN so CI logs distinguish Android Debug from a Play key.
Does not re-sign. Do not unzip/modify/sign APKs by hand.

Customer APKs must be in.gpstore.customer. Admin APKs must be in.gpstore.admin.
Worker APKs must be com.gpstore.worker. Sharing an applicationId would make
installing one replace another.
"""
from __future__ import annotations

import glob
import os
import re
import struct
import subprocess
import sys
import tempfile
import zipfile

# Android 15+ 16 KiB page devices reject PT_LOAD align < 16384.
MIN_ELF_LOAD_ALIGN = 16384
PT_LOAD = 1


CUSTOMER_PACKAGE = "in.gpstore.customer"
ADMIN_PACKAGE = "in.gpstore.admin"
WORKER_PACKAGE = "com.gpstore.worker"

# Only the worker runs a location foreground service. Forbidding the pair in
# the other two APKs is not tidiness: a foreground-service permission that
# arrives by plugin merge drags the whole app into Google Play's Foreground
# Service declaration review, for a service it does not have.
FOREGROUND_LOCATION_PERMISSIONS = {
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.FOREGROUND_SERVICE_LOCATION",
}

CUSTOMER_FORBIDDEN_PERMISSIONS = {
    "android.permission.CAMERA",
    "android.permission.ACCESS_BACKGROUND_LOCATION",
    "android.permission.BLUETOOTH_CONNECT",
    "android.permission.BLUETOOTH_SCAN",
} | FOREGROUND_LOCATION_PERMISSIONS
ADMIN_FORBIDDEN_PERMISSIONS = {
    "android.permission.CAMERA",
    "android.permission.ACCESS_BACKGROUND_LOCATION",
    "android.permission.RECORD_AUDIO",
    "android.permission.ACCESS_FINE_LOCATION",
    "android.permission.ACCESS_COARSE_LOCATION",
} | FOREGROUND_LOCATION_PERMISSIONS
WORKER_FORBIDDEN_PERMISSIONS = {
    # THE ONE THAT MATTERS. A foreground service tracks a rider while they are
    # working; background location would track them when they are not. The
    # whole design rests on that line, and this is what stops a dependency bump
    # quietly crossing it.
    "android.permission.ACCESS_BACKGROUND_LOCATION",
    "android.permission.RECORD_AUDIO",
    "android.permission.BLUETOOTH_CONNECT",
    "android.permission.BLUETOOTH_SCAN",
}
ADMIN_REQUIRED_PERMISSIONS = {
    # Paired thermal printer for order receipts.
    "android.permission.BLUETOOTH_CONNECT",
}
WORKER_REQUIRED_PERMISSIONS = {
    "android.permission.CAMERA",
    # Without these the location service dies on Android 14 the instant it
    # starts - a failure that appears only on the newest phones, only in
    # release, and only once a rider is out on a delivery.
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.FOREGROUND_SERVICE_LOCATION",
    # The service's ongoing notification is hidden without this on API 33+,
    # which would leave a rider tracked with nothing on screen saying so.
    "android.permission.POST_NOTIFICATIONS",
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
    if package == ADMIN_PACKAGE:
        extra = sorted(perms & ADMIN_FORBIDDEN_PERMISSIONS)
        if extra:
            problems.append(f"admin APK must not declare {extra}")
        missing = sorted(ADMIN_REQUIRED_PERMISSIONS - perms)
        if missing:
            problems.append(f"admin APK must declare {missing}")
    if package == WORKER_PACKAGE:
        extra = sorted(perms & WORKER_FORBIDDEN_PERMISSIONS)
        if extra:
            problems.append(f"worker APK must not declare {extra}")
        missing = sorted(WORKER_REQUIRED_PERMISSIONS - perms)
        if missing:
            problems.append(f"worker APK must declare {missing}")
    return problems


# A shipped APK has no business containing a desktop executable. win_ble's
# BLEServer.exe arrives transitively from the thermal-printer package and is
# excluded in android/app/build.gradle; this is what proves the exclusion
# actually worked, because a Gradle property that silently stops matching
# fails in no other visible way.
FORBIDDEN_ASSET_SUFFIXES = (".exe", ".dll", ".msi")


def forbidden_payload_problems(apk: str) -> list[str]:
    problems: list[str] = []
    with zipfile.ZipFile(apk) as archive:
        for entry in archive.namelist():
            lowered = entry.lower()
            if lowered.endswith(FORBIDDEN_ASSET_SUFFIXES):
                problems.append(
                    f"{os.path.basename(apk)} ships a desktop binary: {entry}"
                )
    return problems


def sdk_tool(name: str) -> str:
    sdk = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
    if not sdk:
        sys.exit("ANDROID_SDK_ROOT / ANDROID_HOME is not set")
    matches = sorted(glob.glob(os.path.join(sdk, "build-tools", "*", name)))
    if not matches:
        sys.exit(f"no {name} under {sdk}/build-tools")
    return matches[-1]


def elf_pt_load_alignments(blob: bytes) -> list[int]:
    """Return PT_LOAD p_align values from a 32/64-bit ELF. Raises ValueError."""
    if len(blob) < 16 or blob[:4] != b"\x7fELF":
        raise ValueError("not ELF")
    ei_class = blob[4]  # 1=32, 2=64
    ei_data = blob[5]  # 1=LE, 2=BE
    if ei_class not in (1, 2) or ei_data not in (1, 2):
        raise ValueError("unsupported ELF ident")
    endian = "<" if ei_data == 1 else ">"
    if ei_class == 2:
        if len(blob) < 64:
            raise ValueError("truncated ELF64 header")
        _e_type, _e_machine, _e_version, _e_entry, e_phoff, _e_shoff, _e_flags, _e_ehsize, e_phentsize, e_phnum, _shentsize, _shnum, _shstrndx = struct.unpack_from(
            endian + "HHIQQQIHHHHHH", blob, 16
        )
        phdr_fmt = endian + "IIQQQQQQ"
        phdr_need = 56
        align_index = 7
    else:
        if len(blob) < 52:
            raise ValueError("truncated ELF32 header")
        _e_type, _e_machine, _e_version, _e_entry, e_phoff, _e_shoff, _e_flags, _e_ehsize, e_phentsize, e_phnum, _shentsize, _shnum, _shstrndx = struct.unpack_from(
            endian + "HHIIIIIHHHHHH", blob, 16
        )
        phdr_fmt = endian + "IIIIIIII"
        phdr_need = 32
        align_index = 7
    if e_phentsize < phdr_need or e_phnum < 1:
        raise ValueError("no program headers")
    aligns: list[int] = []
    for i in range(e_phnum):
        off = e_phoff + i * e_phentsize
        if off + phdr_need > len(blob):
            raise ValueError("truncated program header")
        fields = struct.unpack_from(phdr_fmt, blob, off)
        if fields[0] != PT_LOAD:
            continue
        aligns.append(int(fields[align_index]))
    return aligns


def fake_elf64_le(load_aligns: list[int]) -> bytes:
    """Minimal ET_DYN ELF64 for --self-test. Not a runnable library."""
    e_phnum = len(load_aligns)
    e_phoff = 64
    e_phentsize = 56
    header = b"\x7fELF" + bytes([2, 1, 1, 0]) + bytes(8)
    header += struct.pack(
        "<HHIQQQIHHHHHH",
        3,  # ET_DYN
        183,  # EM_AARCH64
        1,
        0,
        e_phoff,
        0,
        0,
        64,
        e_phentsize,
        e_phnum,
        0,
        0,
        0,
    )
    phdrs = b""
    for align in load_aligns:
        phdrs += struct.pack("<IIQQQQQQ", PT_LOAD, 5, 0, 0, 0, 0, 0, align)
    return header + phdrs


def native_lib_elf_problems(apk: str) -> list[str]:
    """Fail native libs whose PT_LOAD alignment is below 16 KiB."""
    problems: list[str] = []
    try:
        with zipfile.ZipFile(apk) as zf:
            names = [
                info.filename
                for info in zf.infolist()
                if info.filename.startswith("lib/") and info.filename.endswith(".so")
            ]
            if not names:
                return ["no lib/**/*.so in APK"]
            for name in names:
                blob = zf.read(name)
                try:
                    aligns = elf_pt_load_alignments(blob)
                except ValueError as exc:
                    problems.append(f"{name}: {exc}")
                    continue
                if not aligns:
                    problems.append(f"{name}: no PT_LOAD")
                    continue
                min_align = min(aligns)
                print(f"ELF_ALIGN {name} min={min_align} loads={aligns}")
                if min_align < MIN_ELF_LOAD_ALIGN:
                    problems.append(
                        f"{name}: PT_LOAD align {min_align} < {MIN_ELF_LOAD_ALIGN} "
                        "(16 KiB page)"
                    )
    except zipfile.BadZipFile as exc:
        return [f"not a zip/apk: {exc}"]
    return problems


def expected_package(apk: str) -> str | None:
    name = os.path.basename(apk).lower()
    if "worker" in name:
        return WORKER_PACKAGE
    if "admin" in name:
        return ADMIN_PACKAGE
    if "customer" in name:
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
        for problem in native_lib_elf_problems(apk):
            print(f"ELF_ALIGN_TOO_SMALL {apk}: {problem}")
            failed = True
        for problem in forbidden_payload_problems(apk):
            print(f"FORBIDDEN_PAYLOAD {problem}")
            failed = True

    worker_pkgs = {pkg for name, pkg in seen.items() if "worker" in name.lower()}
    admin_pkgs = {pkg for name, pkg in seen.items() if "admin" in name.lower()}
    customer_pkgs = {
        pkg for name, pkg in seen.items() if "customer" in name.lower()
    }
    groups = (
        ("worker", worker_pkgs),
        ("admin", admin_pkgs),
        ("customer", customer_pkgs),
    )
    for i, (left_name, left) in enumerate(groups):
        for right_name, right in groups[i + 1 :]:
            overlap = left & right
            if left and right and overlap:
                print(
                    "SHARED_APPLICATION_ID "
                    f"{left_name} and {right_name} APKs must not use "
                    f"the same package: {sorted(overlap)}"
                )
                failed = True
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    # THE BUNDLE IS WHAT GOES TO PLAY. The checks above are APK-specific
    # (signature, zipalign, ELF load alignment), but a desktop binary shipping
    # inside an .aab reaches real phones just the same - and stripping it from
    # the APKs while leaving it in the bundle would be the worst outcome: a
    # green check and a shipped .exe.
    if len(sys.argv) > 2 and sys.argv[1] == "--payload-only":
        failed = False
        for archive in sys.argv[2:]:
            problems = forbidden_payload_problems(archive)
            for problem in problems:
                print(f"FORBIDDEN_PAYLOAD {problem}")
                failed = True
            if not problems:
                print(f"PAYLOAD_OK {os.path.basename(archive)}")
        sys.exit(1 if failed else 0)

    if len(sys.argv) == 2 and sys.argv[1] == "--self-test":
        assert permission_violations(
            CUSTOMER_PACKAGE, {"android.permission.CAMERA"}
        ), "customer CAMERA must fail"
        assert permission_violations(
            CUSTOMER_PACKAGE, {"android.permission.BLUETOOTH_CONNECT"}
        ), "customer Bluetooth must fail"
        assert not permission_violations(
            CUSTOMER_PACKAGE, {"android.permission.INTERNET"}
        )
        assert permission_violations(
            ADMIN_PACKAGE, {"android.permission.RECORD_AUDIO"}
        ), "admin RECORD_AUDIO must fail"
        assert not permission_violations(
            ADMIN_PACKAGE, {"android.permission.BLUETOOTH_CONNECT"}
        )
        assert permission_violations(
            ADMIN_PACKAGE, {"android.permission.ACCESS_FINE_LOCATION"}
        ), "admin has no location feature - it must not ship the permission"
        assert permission_violations(
            WORKER_PACKAGE, {"android.permission.INTERNET"}
        ), "worker missing CAMERA must fail"

        # The complete, correct worker set. Everything below removes exactly
        # one thing from it, so each assertion names a single cause.
        worker_ok = {
            "android.permission.CAMERA",
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.FOREGROUND_SERVICE_LOCATION",
            "android.permission.POST_NOTIFICATIONS",
        }
        assert not permission_violations(WORKER_PACKAGE, worker_ok), permission_violations(
            WORKER_PACKAGE, worker_ok
        )
        for dropped in sorted(WORKER_REQUIRED_PERMISSIONS):
            assert permission_violations(
                WORKER_PACKAGE, worker_ok - {dropped}
            ), f"worker APK without {dropped} must fail"
        assert permission_violations(
            WORKER_PACKAGE, worker_ok | {"android.permission.ACCESS_BACKGROUND_LOCATION"}
        ), "background location must never reach the worker APK"
        assert permission_violations(
            WORKER_PACKAGE,
            {"android.permission.CAMERA", "android.permission.RECORD_AUDIO"},
        )

        # A foreground-service permission merged into the shopper or admin APK
        # would drag it into Play's Foreground Service review for a service it
        # does not run.
        for other in (CUSTOMER_PACKAGE, ADMIN_PACKAGE):
            for granted in sorted(FOREGROUND_LOCATION_PERMISSIONS):
                assert permission_violations(
                    other, {granted}
                ), f"{other} must not declare {granted}"
        assert elf_pt_load_alignments(fake_elf64_le([65536, 65536, 65536])) == [
            65536,
            65536,
            65536,
        ]
        assert elf_pt_load_alignments(fake_elf64_le([16384, 16384, 16384])) == [
            16384,
            16384,
            16384,
        ]
        assert min(elf_pt_load_alignments(fake_elf64_le([4096, 4096]))) < MIN_ELF_LOAD_ALIGN
        try:
            elf_pt_load_alignments(b"not-elf")
            raise AssertionError("non-ELF must raise")
        except ValueError:
            pass
        with tempfile.TemporaryDirectory() as tmp:
            good = os.path.join(tmp, "good.apk")
            bad = os.path.join(tmp, "bad.apk")
            with zipfile.ZipFile(good, "w") as zf:
                zf.writestr(
                    "lib/arm64-v8a/libimage_processing_util_jni.so",
                    fake_elf64_le([16384, 16384, 16384]),
                )
            with zipfile.ZipFile(bad, "w") as zf:
                zf.writestr(
                    "lib/arm64-v8a/libimage_processing_util_jni.so",
                    fake_elf64_le([4096, 4096, 4096]),
                )
            assert not native_lib_elf_problems(good), native_lib_elf_problems(good)
            assert native_lib_elf_problems(bad), "4 KiB CameraX jni must fail"

            assert not forbidden_payload_problems(good), forbidden_payload_problems(good)
            windows = os.path.join(tmp, "windows.apk")
            with zipfile.ZipFile(windows, "w") as zf:
                # The real path win_ble's asset lands at.
                zf.writestr(
                    "assets/flutter_assets/packages/win_ble/assets/BLEServer.exe",
                    b"MZ",
                )
            assert forbidden_payload_problems(windows), "BLEServer.exe must fail"
            mixed_case = os.path.join(tmp, "mixed.apk")
            with zipfile.ZipFile(mixed_case, "w") as zf:
                zf.writestr("assets/flutter_assets/packages/win_ble/BLEServer.EXE", b"MZ")
            assert forbidden_payload_problems(mixed_case), "case must not be a way past this"

            # Same scan must work on a bundle, which is a zip like any other.
            bundle = os.path.join(tmp, "app.aab")
            with zipfile.ZipFile(bundle, "w") as zf:
                zf.writestr("base/assets/flutter_assets/packages/win_ble/assets/BLEServer.exe", b"MZ")
            assert forbidden_payload_problems(bundle), "an .aab must be scanned too"
            clean_bundle = os.path.join(tmp, "clean.aab")
            with zipfile.ZipFile(clean_bundle, "w") as zf:
                zf.writestr("base/assets/flutter_assets/AssetManifest.json", b"{}")
            assert not forbidden_payload_problems(clean_bundle)
        print("self-test ok")
        sys.exit(0)
    main()
