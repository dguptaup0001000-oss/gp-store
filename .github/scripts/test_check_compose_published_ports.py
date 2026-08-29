#!/usr/bin/env python3
"""Unit tests for check_compose_published_ports.py.

The wildcard-publish fixtures must fail. Loopback publishes must pass.
"""
from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPT_DIR))

from check_compose_published_ports import (  # noqa: E402
    check_tree,
    violations_in_text,
)


WILDCARD = """
services:
  postgres:
    image: postgres:17
    ports:
      - "5432:5432"
"""

LOOPBACK = """
name: gpstore-local
services:
  postgres:
    image: postgres:17
    ports:
      - "127.0.0.1:5432:5432"
  redis:
    image: redis:7-alpine
    ports:
      - "127.0.0.1:6379:6379"
  backend:
    image: gp-store-backend
    ports:
      - "127.0.0.1:8081:8081"
"""

BIND_ALL = """
services:
  postgres:
    ports:
      - "0.0.0.0:5432:5432"
"""

IPV6_ALL = """
services:
  redis:
    ports:
      - "[::]:6379:6379"
"""

LONG_UNBOUND = """
services:
  backend:
    ports:
      - target: 8081
        published: 8081
        protocol: tcp
"""

LONG_LOOPBACK = """
services:
  backend:
    ports:
      - target: 8081
        published: 8081
        host_ip: 127.0.0.1
        protocol: tcp
"""

TRAEFIK_OK = """
name: gpstore
services:
  traefik:
    ports:
      - "80:80"
      - "443:443"
  backend:
    image: gp-store-backend
"""

BARE = """
services:
  postgres:
    ports:
      - 5432
"""


class PublishedPortParsingTests(unittest.TestCase):
    def test_wildcard_short_form_fails(self) -> None:
        hits = violations_in_text(WILDCARD, "fixture.yml")
        self.assertTrue(hits, "5432:5432 must fail (implicit 0.0.0.0)")
        self.assertTrue(any("5432" in h for h in hits))

    def test_explicit_bind_all_fails(self) -> None:
        hits = violations_in_text(BIND_ALL, "fixture.yml")
        self.assertTrue(hits)
        self.assertTrue(any("5432" in h for h in hits))

    def test_ipv6_bind_all_fails(self) -> None:
        hits = violations_in_text(IPV6_ALL, "fixture.yml")
        self.assertTrue(hits)
        self.assertTrue(any("6379" in h for h in hits))

    def test_bare_port_fails(self) -> None:
        hits = violations_in_text(BARE, "fixture.yml")
        self.assertTrue(hits)

    def test_long_form_without_host_ip_fails(self) -> None:
        hits = violations_in_text(LONG_UNBOUND, "fixture.yml")
        self.assertTrue(hits, "long-form published: 8081 without host_ip must fail")

    def test_loopback_short_form_passes(self) -> None:
        self.assertEqual(violations_in_text(LOOPBACK, "fixture.yml"), [])

    def test_long_form_loopback_passes(self) -> None:
        self.assertEqual(violations_in_text(LONG_LOOPBACK, "fixture.yml"), [])

    def test_traefik_80_443_allowed(self) -> None:
        self.assertEqual(violations_in_text(TRAEFIK_OK, "fixture.yml"), [])


class RepoTreeTests(unittest.TestCase):
    def test_default_compose_filename_at_root_fails(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            (root / "docker-compose.yml").write_text(LOOPBACK, encoding="utf-8")
            hits = check_tree(root)
            self.assertTrue(
                any("default Compose filename" in h for h in hits),
                hits,
            )

    def test_local_file_requires_project_name(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            (root / "docker-compose.local.yml").write_text(
                LOOPBACK.replace("name: gpstore-local\n", ""),
                encoding="utf-8",
            )
            hits = check_tree(root)
            self.assertTrue(any("gpstore-local" in h for h in hits), hits)

    def test_compliant_tree_passes(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            (root / "docker-compose.local.yml").write_text(LOOPBACK, encoding="utf-8")
            (root / "backend").mkdir()
            (root / "backend" / "docker-compose.yml").write_text(TRAEFIK_OK, encoding="utf-8")
            self.assertEqual(check_tree(root), [])


class LiveRepoTests(unittest.TestCase):
    def test_this_repository_is_compliant(self) -> None:
        root = SCRIPT_DIR.parents[1]
        self.assertFalse(
            (root / "docker-compose.yml").exists(),
            "repo-root docker-compose.yml must not exist",
        )
        self.assertEqual(check_tree(root), [])


if __name__ == "__main__":
    unittest.main()
