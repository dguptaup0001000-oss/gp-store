#!/usr/bin/env python3
"""Print the largest compressed entries in an APK or AAB."""
from __future__ import annotations

import os
import sys
import zipfile


def main() -> None:
    if len(sys.argv) != 2:
        print("usage: apk_size_report.py <apk-or-aab>", file=sys.stderr)
        sys.exit(2)
    path = sys.argv[1]
    size = os.path.getsize(path)
    print(f"{os.path.basename(path)}: {size} bytes ({size / 1024 / 1024:.2f} MB)")
    with zipfile.ZipFile(path) as z:
        items = sorted(z.infolist(), key=lambda i: -i.compress_size)
    print("top 15 compressed:")
    for i in items[:15]:
        print(
            f"  {i.compress_size / 1024 / 1024:7.3f} MB  "
            f"unpacked {i.file_size / 1024 / 1024:7.3f}  {i.filename}"
        )


if __name__ == "__main__":
    main()
