#!/usr/bin/env python3
"""Checks Redis secret materialization without printing the password."""
from __future__ import annotations

import os
import stat
import subprocess
import sys
import tempfile
from pathlib import Path

SCRIPT = Path(__file__).resolve().parent / "materialize-password-file.py"


def run_materialize(compose_dir: Path) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [sys.executable, str(SCRIPT), str(compose_dir)],
        check=False,
        text=True,
        capture_output=True,
    )


def main() -> int:
    with tempfile.TemporaryDirectory() as raw:
        compose_dir = Path(raw)
        (compose_dir / ".env").write_text(
            "DB_PASSWORD=not-redis\nREDIS_PASSWORD=s3cret-value-for-test\n",
            encoding="utf-8",
        )
        proc = run_materialize(compose_dir)
        if proc.returncode != 0:
            print(proc.stderr or proc.stdout, file=sys.stderr)
            return 1
        if "s3cret-value-for-test" in proc.stdout + proc.stderr:
            print("materialize logged the Redis password", file=sys.stderr)
            return 1
        secret = compose_dir / ".secrets" / "redis_password"
        if secret.read_text(encoding="utf-8").strip() != "s3cret-value-for-test":
            print("secret file contents do not match .env", file=sys.stderr)
            return 1
        mode = secret.stat().st_mode
        if stat.S_IMODE(mode) != 0o644:
            print(f"secret file mode {oct(stat.S_IMODE(mode))} != 0644", file=sys.stderr)
            return 1
        if stat.S_IMODE((compose_dir / ".secrets").stat().st_mode) != 0o700:
            print(".secrets directory is not 0700", file=sys.stderr)
            return 1
        if os.access(secret, os.R_OK) is False:
            print("owner cannot read secret file", file=sys.stderr)
            return 1

        # Rematerialize must overwrite a previous 0600 file (VPS after #96).
        os.chmod(secret, 0o600)
        proc = run_materialize(compose_dir)
        if proc.returncode != 0:
            print(proc.stderr or proc.stdout, file=sys.stderr)
            return 1
        if stat.S_IMODE(secret.stat().st_mode) != 0o644:
            print("rematerialize did not restore 0644", file=sys.stderr)
            return 1
    print("materialize-password-file.py checks passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
