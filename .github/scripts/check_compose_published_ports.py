#!/usr/bin/env python3
"""Fail if any Compose file publishes 5432, 6379, or 8081 off loopback.

A host publish of ``5432:5432`` (no bind address) is 0.0.0.0. Docker's
port publishing bypasses UFW, so that is internet-reachable on a VPS.
Loopback publishes (127.0.0.1 / ::1 / localhost) are allowed for laptop
Compose. Production ``backend/docker-compose.yml`` must not publish these
ports at all (see check_compose_host_ports.py).
"""
from __future__ import annotations

import pathlib
import re
import sys

FORBIDDEN_HOST_PORTS = {5432, 6379, 8081}
LOOPBACK = {"127.0.0.1", "::1", "localhost"}
BIND_ALL = {"0.0.0.0", "::", "*", ""}

COMPOSE_NAMES = {
    "docker-compose.yml",
    "docker-compose.yaml",
    "compose.yml",
    "compose.yaml",
}

ROOT_DEFAULT_COMPOSE = (
    "docker-compose.yml",
    "docker-compose.yaml",
    "compose.yml",
    "compose.yaml",
)

SHORT_SPEC = re.compile(
    r"""
    ^\s*-\s*
    (?P<q>['"])?
    (?:
        \[(?P<ip6>[^\]]+)\]:
        |
        (?P<ip4>(?:\d{1,3}\.){3}\d{1,3}):
        |
        (?P<hostname>localhost):
    )?
    (?P<host>\d+)
    :
    (?P<container>\d+)
    (?:/(?P<proto>[A-Za-z0-9]+))?
    (?P=q)?
    \s*$
    """,
    re.VERBOSE,
)

BARE_PORT = re.compile(
    r"""^\s*-\s*(?P<q>['"])?(?P<port>\d+)(?:/(?P<proto>[A-Za-z0-9]+))?(?P=q)?\s*$"""
)

LONG_PUBLISHED = re.compile(
    r"(?m)^\s+published:\s*['\"]?(?P<port>\d+)['\"]?\s*$"
)
LONG_HOST_IP = re.compile(
    r"(?m)^\s+host_ip:\s*['\"]?(?P<ip>[^'\"\s]+)['\"]?\s*$"
)


def strip_comment(line: str) -> str:
    in_single = False
    in_double = False
    out = []
    i = 0
    while i < len(line):
        ch = line[i]
        if ch == "'" and not in_double:
            in_single = not in_single
            out.append(ch)
        elif ch == '"' and not in_single:
            in_double = not in_double
            out.append(ch)
        elif ch == "#" and not in_single and not in_double:
            break
        else:
            out.append(ch)
        i += 1
    return "".join(out).rstrip()


def is_compose_file(path: pathlib.Path) -> bool:
    name = path.name
    if name in COMPOSE_NAMES:
        return True
    if name.startswith("docker-compose") and name.endswith((".yml", ".yaml")):
        return True
    if name.startswith("compose.") and name.endswith((".yml", ".yaml")):
        return True
    return False


def iter_compose_files(root: pathlib.Path) -> list[pathlib.Path]:
    found = []
    skip_dirs = {".git", "node_modules", "target", ".idea", "__pycache__"}
    for dirpath, dirnames, filenames in os_walk(root, skip_dirs):
        for filename in filenames:
            path = dirpath / filename
            if is_compose_file(path):
                found.append(path)
    return sorted(found)


def os_walk(root: pathlib.Path, skip_dirs: set[str]):
    for dirpath, dirnames, filenames in __import__("os").walk(root):
        dirnames[:] = [d for d in dirnames if d not in skip_dirs]
        yield pathlib.Path(dirpath), dirnames, filenames


def host_is_safe(ip: str | None) -> bool:
    if ip is None:
        return False
    return ip.lower() in LOOPBACK


def violations_in_text(text: str, label: str) -> list[str]:
    found: list[str] = []
    lines = [strip_comment(raw) for raw in text.splitlines()]
    i = 0
    while i < len(lines):
        line = lines[i]
        if re.match(r"^\s+ports:\s*$", line):
            i += 1
            while i < len(lines):
                item = lines[i]
                if not item.strip():
                    i += 1
                    continue
                indent = len(item) - len(item.lstrip(" "))
                if indent < 4:
                    break
                if item.lstrip().startswith("- "):
                    spec = item.strip()
                    short = SHORT_SPEC.match(spec)
                    bare = BARE_PORT.match(spec)
                    if short:
                        host_port = int(short.group("host"))
                        if host_port in FORBIDDEN_HOST_PORTS:
                            ip = short.group("ip6") or short.group("ip4") or short.group("hostname")
                            if ip is not None and ip in BIND_ALL:
                                ip = None
                            if not host_is_safe(ip):
                                found.append(
                                    f"{label}: publishes {host_port} on {ip or '0.0.0.0'} ({spec.strip()})"
                                )
                    elif bare:
                        host_port = int(bare.group("port"))
                        if host_port in FORBIDDEN_HOST_PORTS:
                            found.append(
                                f"{label}: publishes {host_port} on 0.0.0.0 ({spec.strip()})"
                            )
                    else:
                        # Long-form mapping starting at this dash.
                        block = [item]
                        j = i + 1
                        while j < len(lines):
                            nxt = lines[j]
                            if not nxt.strip():
                                j += 1
                                continue
                            nindent = len(nxt) - len(nxt.lstrip(" "))
                            if nindent <= indent and nxt.lstrip().startswith("- "):
                                break
                            if nindent < indent:
                                break
                            if nindent <= indent and re.match(r"^\s+\w", nxt) and not nxt.lstrip().startswith("-"):
                                # next key at ports indent
                                if nindent <= 4:
                                    break
                            block.append(nxt)
                            j += 1
                        blob = "\n".join(block)
                        pub = LONG_PUBLISHED.search(blob)
                        if pub:
                            host_port = int(pub.group("port"))
                            if host_port in FORBIDDEN_HOST_PORTS:
                                hip = LONG_HOST_IP.search(blob)
                                ip = hip.group("ip") if hip else None
                                if ip in BIND_ALL:
                                    ip = None
                                if not host_is_safe(ip):
                                    found.append(
                                        f"{label}: publishes {host_port} on {ip or '0.0.0.0'} (long-form)"
                                    )
                        i = j - 1
                i += 1
            continue
        i += 1
    return found


def check_root_defaults(root: pathlib.Path) -> list[str]:
    found = []
    for name in ROOT_DEFAULT_COMPOSE:
        path = root / name
        if path.is_file():
            found.append(
                f"{path}: default Compose filename must not exist at repo root "
                "(rename to docker-compose.local.yml)"
            )
    return found


def check_local_project_name(root: pathlib.Path) -> list[str]:
    path = root / "docker-compose.local.yml"
    if not path.is_file():
        return [f"{path}: missing (laptop Compose must be docker-compose.local.yml)"]
    text = path.read_text(encoding="utf-8")
    if not re.search(r"(?m)^name:\s*gpstore-local\s*$", text):
        return [f"{path}: must set top-level name: gpstore-local"]
    return []


def check_tree(root: pathlib.Path) -> list[str]:
    found = []
    found.extend(check_root_defaults(root))
    found.extend(check_local_project_name(root))
    for path in iter_compose_files(root):
        rel = path.relative_to(root) if path.is_relative_to(root) else path
        found.extend(violations_in_text(path.read_text(encoding="utf-8"), str(rel)))
    return found


def main(argv: list[str] | None = None) -> int:
    args = sys.argv[1:] if argv is None else argv
    root = pathlib.Path(args[0] if args else ".").resolve()
    found = check_tree(root)
    if found:
        for item in found:
            print(item, file=sys.stderr)
        return 1
    print("compose_published_ports_ok")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
