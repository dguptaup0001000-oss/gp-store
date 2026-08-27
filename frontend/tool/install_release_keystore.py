#!/usr/bin/env python3
"""Install the operator-provided release keystore for CI. Never invent one.

Reads ANDROID_KEYSTORE_BASE64 / ANDROID_KEYSTORE_PASSWORD / ANDROID_KEY_PASSWORD
/ ANDROID_KEY_ALIAS from the environment and writes android/upload-keystore.jks
plus android/key.properties. Does not print secret values, file contents, or
decoded bytes.

Play Console is not required. This only enables Gradle signingConfigs.release.
"""
from __future__ import annotations

import base64
import os
import stat
import sys
from pathlib import Path

JKS_MAGIC = b"\xfe\xed\xfe\xed"
_B64_KEEP = set("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=")


def _die(message: str) -> None:
    prefix = "::error::" if "--self-test" not in sys.argv else "expected failure: "
    print(f"{prefix}{message}", file=sys.stderr)
    raise SystemExit(1)


def decode_keystore_b64(raw: str) -> bytes:
    """Decode ANDROID_KEYSTORE_BASE64. Never log the input.

    Termux/openssl wrap lines. GitHub secret fields sometimes turn '+' into
    spaces. validate=True rejected those and failed the first signed APK job.
    """
    s = (raw or "").replace("\ufeff", "").strip()
    if (s.startswith('"') and s.endswith('"')) or (
        s.startswith("'") and s.endswith("'")
    ):
        s = s[1:-1]
    # Keep newlines/tabs as wrapping; treat remaining spaces as '+'.
    s = s.replace("\r", "").replace("\n", "").replace("\t", "")
    s = s.replace("-", "+").replace("_", "/").replace(" ", "+")
    s = "".join(ch for ch in s if ch in _B64_KEEP)
    if not s:
        raise ValueError("empty")
    s += "=" * ((-len(s)) % 4)
    return base64.b64decode(s)


def _looks_like_keystore(blob: bytes) -> bool:
    if len(blob) < 32:
        return False
    if blob.startswith(JKS_MAGIC):
        return True
    # PKCS12 is a DER SEQUENCE (Termux keytool -storetype PKCS12).
    return blob[0] == 0x30


def install(android_dir: Path) -> None:
    b64 = os.environ.get("ANDROID_KEYSTORE_BASE64") or ""
    if not b64.strip():
        print("ANDROID_KEYSTORE_BASE64 unset; skipping release keystore")
        return

    password = os.environ.get("ANDROID_KEYSTORE_PASSWORD") or ""
    key_password = os.environ.get("ANDROID_KEY_PASSWORD") or ""
    alias = os.environ.get("ANDROID_KEY_ALIAS") or ""
    missing = [
        name
        for name, value in (
            ("ANDROID_KEYSTORE_PASSWORD", password),
            ("ANDROID_KEY_PASSWORD", key_password),
            ("ANDROID_KEY_ALIAS", alias),
        )
        if not value.strip()
    ]
    if missing:
        _die(
            "required when ANDROID_KEYSTORE_BASE64 is set: " + ", ".join(missing)
        )
    if alias != alias.strip() or any(ch.isspace() for ch in alias):
        _die("ANDROID_KEY_ALIAS must be a single token with no whitespace")

    try:
        blob = decode_keystore_b64(b64)
    except ValueError:
        _die("ANDROID_KEYSTORE_BASE64 is not valid base64")

    if not _looks_like_keystore(blob):
        _die(
            "ANDROID_KEYSTORE_BASE64 did not decode to a JKS or PKCS12 keystore"
        )

    android_dir.mkdir(parents=True, exist_ok=True)
    keystore_path = android_dir / "upload-keystore.jks"
    props_path = android_dir / "key.properties"
    keystore_path.write_bytes(blob)
    props_path.write_text(
        "storePassword="
        + password
        + "\nkeyPassword="
        + key_password
        + "\nkeyAlias="
        + alias
        + "\nstoreFile=upload-keystore.jks\n",
        encoding="utf-8",
    )
    os.chmod(keystore_path, stat.S_IRUSR | stat.S_IWUSR)
    os.chmod(props_path, stat.S_IRUSR | stat.S_IWUSR)

    keys = []
    for line in props_path.read_text(encoding="utf-8").splitlines():
        if "=" not in line:
            _die("key.properties is malformed")
        key, _, value = line.partition("=")
        if not value:
            _die("key.properties is missing a value")
        keys.append(key)
    if keys != ["storePassword", "keyPassword", "keyAlias", "storeFile"]:
        _die("key.properties keys are not the expected signing fields")
    print(
        "Release keystore installed for Gradle (PKCS12/JKS, "
        f"{keystore_path.stat().st_size} bytes). Passwords are not logged."
    )


def _self_test() -> None:
    import tempfile

    fake = b"\x30" + b"\x00" * 64
    encoded = base64.b64encode(fake).decode("ascii")
    assert decode_keystore_b64(encoded) == fake
    wrapped = "\n".join(encoded[i : i + 16] for i in range(0, len(encoded), 16))
    assert decode_keystore_b64("\ufeff" + wrapped) == fake
    plus = encoded.replace("A", "+") if "A" in encoded else encoded
    if "+" in plus:
        assert decode_keystore_b64(plus.replace("+", " ")) == decode_keystore_b64(plus)
    os.environ["ANDROID_KEYSTORE_BASE64"] = wrapped
    os.environ["ANDROID_KEYSTORE_PASSWORD"] = "unit-test-store"
    os.environ["ANDROID_KEY_PASSWORD"] = "unit-test-store"
    os.environ["ANDROID_KEY_ALIAS"] = "gpstore"
    with tempfile.TemporaryDirectory() as tmp:
        android = Path(tmp) / "android"
        install(android)
        keystore = android / "upload-keystore.jks"
        props = android / "key.properties"
        assert keystore.read_bytes() == fake
        text = props.read_text(encoding="utf-8")
        assert "storeFile=upload-keystore.jks" in text
        assert "keyAlias=gpstore" in text
        assert oct(keystore.stat().st_mode & 0o777) == "0o600"
        assert oct(props.stat().st_mode & 0o777) == "0o600"
    os.environ["ANDROID_KEYSTORE_BASE64"] = "not-base64!!"
    try:
        install(Path(tempfile.mkdtemp()) / "android")
    except SystemExit:
        pass
    else:
        raise AssertionError("invalid base64 must fail")
    print("self-test ok")


if __name__ == "__main__":
    if len(sys.argv) == 2 and sys.argv[1] == "--self-test":
        _self_test()
        raise SystemExit(0)
    install(Path("android"))
