#!/usr/bin/env python3
"""Fail if the customer Dart graph imports admin UI, or the admin graph imports shopping.

Tree-shaking only drops unreachable Dart. A single import of AdminHomeScreen
from the customer entrypoint would ship store management in the shop APK.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

LIB = Path(__file__).resolve().parents[1] / "lib"
IMPORT_RE = re.compile(r"""^\s*import\s+['"]([^'"]+)['"]""", re.M)

CUSTOMER_ENTRY = LIB / "customer_main.dart"
ADMIN_ENTRY = LIB / "admin_main.dart"

CUSTOMER_FORBIDDEN = (
    "/features/admin/",
    "admin_home_screen.dart",
    "admin_order_detail_screen.dart",
    "delivery_dashboard_screen.dart",
    "admin_order_sound_watcher.dart",
    "printer_providers.dart",
    "printer_service.dart",
)

ADMIN_FORBIDDEN = (
    "customer_shell.dart",
    "register_screen.dart",
    "/features/cart/",
    "/features/checkout/",
    "/features/wishlist/",
    "/features/home/presentation/home_screen.dart",
    "delivery_dashboard_screen.dart",
)


def resolve_import(current: Path, spec: str) -> Path | None:
    if spec.startswith("dart:") or spec.startswith("package:flutter") or spec.startswith("package:firebase"):
        return None
    if spec.startswith("package:gpstore/"):
        return LIB / spec[len("package:gpstore/") :]
    if spec.startswith("package:"):
        return None
    return (current.parent / spec).resolve()


def walk(entry: Path) -> set[Path]:
    seen: set[Path] = set()
    stack = [entry.resolve()]
    while stack:
        path = stack.pop()
        if path in seen or not path.is_file() or path.suffix != ".dart":
            continue
        seen.add(path)
        text = path.read_text(encoding="utf-8")
        for spec in IMPORT_RE.findall(text):
            nxt = resolve_import(path, spec)
            if nxt is None:
                continue
            try:
                nxt.relative_to(LIB)
            except ValueError:
                continue
            stack.append(nxt)
    return seen


def violations(entry: Path, forbidden: tuple[str, ...]) -> list[str]:
    problems: list[str] = []
    for path in sorted(walk(entry)):
        rel = path.relative_to(LIB).as_posix()
        blob = f"/{rel}"
        for needle in forbidden:
            if needle in blob or needle in rel:
                problems.append(f"{entry.name} reaches {rel} ({needle})")
    return problems


def main() -> int:
    if len(sys.argv) == 2 and sys.argv[1] == "--self-test":
        customer = walk(CUSTOMER_ENTRY)
        admin = walk(ADMIN_ENTRY)
        assert any(p.name == "customer_app.dart" for p in customer), customer
        assert any(p.name == "admin_app.dart" for p in admin), admin
        print("assert_app_separation.py self-test ok")
        return 0

    failed = False
    for problem in violations(CUSTOMER_ENTRY, CUSTOMER_FORBIDDEN):
        print(f"CUSTOMER_IMPORTS_ADMIN {problem}")
        failed = True
    for problem in violations(ADMIN_ENTRY, ADMIN_FORBIDDEN):
        print(f"ADMIN_IMPORTS_SHOP {problem}")
        failed = True
    profile = (LIB / "features/profile/presentation/profile_screen.dart").read_text(
        encoding="utf-8"
    )
    if "Store Management" in profile or "admin_home_screen" in profile:
        print("PROFILE_HAS_ADMIN_ENTRY profile_screen.dart still links Store Management")
        failed = True
    if failed:
        return 1
    print("customer and admin Dart graphs are separate")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
