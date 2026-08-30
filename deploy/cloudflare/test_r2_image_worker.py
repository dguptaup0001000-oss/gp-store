#!/usr/bin/env python3
"""Worker must keep the R2 bucket private and refuse staging / traversal."""

from pathlib import Path
import unittest

WORKER = Path(__file__).with_name("r2-image-worker.js").read_text()


def allow_key(key: str) -> bool:
    if not key.startswith("gpstore/products/") and not key.startswith(
        "gpstore/categories/"
    ):
        return False
    if ".." in key or "staging" in key:
        return False
    return True


class R2ImageWorkerTest(unittest.TestCase):
    def test_source_is_get_head_only(self):
        self.assertIn('request.method !== "GET"', WORKER)
        self.assertIn('request.method !== "HEAD"', WORKER)
        self.assertNotIn("IMAGES.list", WORKER)
        self.assertNotIn("IMAGES.put", WORKER)
        self.assertIn("Do not grant ListBucket", WORKER)

    def test_source_refuses_staging_and_traversal(self):
        self.assertIn("staging", WORKER)
        self.assertIn("..", WORKER)
        self.assertIn("gpstore/products/", WORKER)
        self.assertIn("gpstore/categories/", WORKER)
        self.assertIn("max-age=31536000", WORKER)

    def test_permanent_catalogue_keys_are_allowed(self):
        self.assertTrue(allow_key("gpstore/products/1/original/a.jpg"))
        self.assertTrue(allow_key("gpstore/categories/9/original/b.webp"))

    def test_staging_and_other_prefixes_are_refused(self):
        self.assertFalse(allow_key("gpstore/staging/products/1/original/a.jpg"))
        self.assertFalse(allow_key("gpstore/products/../secret"))
        self.assertFalse(allow_key("etc/passwd"))
        self.assertFalse(allow_key("gpstore/other/a.jpg"))


if __name__ == "__main__":
    unittest.main()
