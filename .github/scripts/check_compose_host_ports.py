#!/usr/bin/env python3
"""Refuse production Compose services that publish host ports.

Postgres, Redis, and the backend must stay on the Docker network only.
Traefik may publish 80/443.
"""
from __future__ import annotations

import pathlib
import re
import sys

FORBIDDEN = ("postgres", "redis", "backend")


def service_blocks(text: str) -> dict[str, str]:
    cleaned = []
    for raw in text.splitlines():
        stripped = raw.split("#", 1)[0].rstrip()
        cleaned.append(stripped)
    body = "\n".join(cleaned)
    parts = re.split(r"\n(?=  [A-Za-z0-9_-]+:)", "\n" + body)
    blocks: dict[str, str] = {}
    for part in parts:
        match = re.match(r"\s{2}([A-Za-z0-9_-]+):", part)
        if match:
            blocks[match.group(1)] = part
    return blocks


def main() -> int:
    path = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "backend/docker-compose.yml")
    blocks = service_blocks(path.read_text())
    for name in FORBIDDEN:
        if name not in blocks:
            print(f"missing service {name}", file=sys.stderr)
            return 1
        if re.search(r"(?m)^    ports:", blocks[name]):
            print(f"{name}: must not publish host ports", file=sys.stderr)
            return 1
    print("compose_host_ports_ok")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
