#!/usr/bin/env python3
"""Copy REDIS_PASSWORD from backend/.env into .secrets/redis_password.

Compose mounts that file as a Docker secret so the password is not in
container Env or redis-server argv (docker inspect). The value is never
printed.

Usage: materialize-password-file.py <compose-dir>
"""
from __future__ import annotations

import os
import stat
import sys
from pathlib import Path


def main() -> int:
    if len(sys.argv) != 2:
        print("Usage: materialize-password-file.py <compose-dir>", file=sys.stderr)
        return 2
    compose_dir = Path(sys.argv[1])
    env_file = compose_dir / ".env"
    if not env_file.is_file():
        print(f"Missing {env_file} (production secrets). This file is never in git.", file=sys.stderr)
        return 1

    password = None
    for raw in env_file.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("REDIS_PASSWORD="):
            password = line.split("=", 1)[1]
            if len(password) >= 2 and password[0] == password[-1] and password[0] in "\"'":
                password = password[1:-1]
            break

    if not password:
        print("REDIS_PASSWORD is missing or empty in backend/.env", file=sys.stderr)
        return 1

    secret_dir = compose_dir / ".secrets"
    secret_dir.mkdir(mode=0o700, exist_ok=True)
    os.chmod(secret_dir, 0o700)
    secret_file = secret_dir / "redis_password"
    secret_file.write_text(password + "\n", encoding="utf-8")
    # Compose bind-mounts this file into /run/secrets with the host mode.
    # 0600 root:root is unreadable by backend appuser and by redis after
    # gosu. 0444 inside a 0700 directory: only the VPS owner can enter
    # .secrets; the containers that receive the mount can read the file.
    # 0644: owner can rematerialize; group/other read so appuser and redis
    # can read the bind-mounted file. Directory stays 0700.
    os.chmod(secret_file, stat.S_IRUSR | stat.S_IWUSR | stat.S_IRGRP | stat.S_IROTH)
    # Confirm we wrote a non-empty file without printing the value.
    if secret_file.stat().st_size < 2:
        print("Failed to write Redis password file", file=sys.stderr)
        return 1
    mode = secret_file.stat().st_mode & 0o777
    if mode != 0o644:
        print(f"Redis password file mode is {oct(mode)}, expected 0o644", file=sys.stderr)
        return 1
    print("Redis password file materialized (value not logged).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
