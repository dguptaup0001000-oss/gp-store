#!/usr/bin/env python3
"""Rename Flutter --split-per-abi flavor APKs to stable artifact names.

Flutter 3.35 emits app-<abi>-<flavor>-release.apk. Older toolchains used
app-<flavor>-<abi>-release.apk. Accept both so CI does not depend on the
order.
"""
from __future__ import annotations

import sys
from pathlib import Path


def find_split_apk(out: Path, flavor: str, abi: str) -> Path:
    candidates = [
        out / f"app-{flavor}-{abi}-release.apk",
        out / f"app-{abi}-{flavor}-release.apk",
    ]
    for path in candidates:
        if path.is_file():
            return path
    names = ", ".join(p.name for p in candidates)
    raise FileNotFoundError(f"missing {flavor} {abi} APK in {out}: tried {names}")


def rename_flavor(out: Path, flavor: str, dest_arm64: str, dest_armv7: str) -> None:
    find_split_apk(out, flavor, "arm64-v8a").rename(out / dest_arm64)
    find_split_apk(out, flavor, "armeabi-v7a").rename(out / dest_armv7)


def main(argv: list[str]) -> int:
    if len(argv) == 2 and argv[1] == "--self-test":
        import tempfile

        with tempfile.TemporaryDirectory() as tmp:
            d = Path(tmp)
            (d / "app-arm64-v8a-worker-release.apk").write_bytes(b"a")
            (d / "app-armeabi-v7a-worker-release.apk").write_bytes(b"b")
            rename_flavor(d, "worker", "gpstore-worker-arm64.apk", "gpstore-worker-armv7.apk")
            assert (d / "gpstore-worker-arm64.apk").read_bytes() == b"a"
            (d / "app-customer-arm64-v8a-release.apk").write_bytes(b"c")
            (d / "app-customer-armeabi-v7a-release.apk").write_bytes(b"d")
            rename_flavor(
                d, "customer", "gpstore-customer-release.apk", "gpstore-customer-armv7.apk"
            )
            assert (d / "gpstore-customer-release.apk").read_bytes() == b"c"
        print("rename_flavor_apks.py self-test ok")
        return 0
    if len(argv) < 5 or (len(argv) - 2) % 3 != 0:
        print(
            "usage: rename_flavor_apks.py <dir> <flavor> <dest-arm64> <dest-armv7> [...]",
            file=sys.stderr,
        )
        return 2
    out = Path(argv[1])
    for i in range(2, len(argv), 3):
        flavor, dest64, destv7 = argv[i], argv[i + 1], argv[i + 2]
        try:
            rename_flavor(out, flavor, dest64, destv7)
        except FileNotFoundError as exc:
            print(f"::error::{exc}", file=sys.stderr)
            print("present:", sorted(p.name for p in out.glob("*.apk")), file=sys.stderr)
            return 1
        print(f"renamed {flavor} -> {dest64}, {destv7}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
