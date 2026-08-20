#!/usr/bin/env python3
"""Promote the validated preview.2 core binary to preview.9 metadata and touchpad-first entry.

Only equal-length replacements are allowed. The script refuses unknown inputs,
never rewrites managed layout, and does not alter the existing text-injection
implementation.
"""
from __future__ import annotations

import hashlib
import sys
from pathlib import Path

EXPECTED_SHA256 = "486c0fc088d44af22b158dd04c1495b096d9a989955be9f939a5107efe8701e4"

REPLACEMENTS = [
    (b"1.3.0-preview.2", b"1.3.0-preview.9", 1, "informational version ASCII"),
    ("1.3.0-preview.2".encode("utf-16le"), "1.3.0-preview.9".encode("utf-16le"), 2, "page/product version UTF-16"),
    (b"1.3.0.2", b"1.3.0.9", 1, "assembly version ASCII"),
    ("1.3.0.2".encode("utf-16le"), "1.3.0.9".encode("utf-16le"), 4, "assembly/native version UTF-16"),
    ("phone-input-v1.3.0".encode("utf-16le"), "phone-input-v1.3.9".encode("utf-16le"), 2, "page cache revision"),
    (
        "$('#touchpad').onclick=()=>location.href='http://'+location.hostname+':51877/';".encode("utf-16le"),
        "location.href='http://'+location.hostname+':51877/';/*default-touchpad-home*/  ".encode("utf-16le"),
        2,
        "default mobile entry redirect",
    ),
]


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def patch(source: Path, output: Path) -> None:
    original = source.read_bytes()
    actual_hash = sha256(original)
    if actual_hash != EXPECTED_SHA256:
        raise RuntimeError(f"unexpected preview.2 core SHA-256: {actual_hash}")

    data = bytearray(original)
    for old, new, expected_count, label in REPLACEMENTS:
        if len(old) != len(new):
            raise RuntimeError(f"{label}: replacement length mismatch")
        count = data.count(old)
        if count != expected_count:
            raise RuntimeError(f"{label}: expected {expected_count} matches, found {count}")
        data[:] = data.replace(old, new)

    result = bytes(data)
    for old, new, _, label in REPLACEMENTS:
        if old in result:
            raise RuntimeError(f"{label}: old marker remains")
        if new not in result:
            raise RuntimeError(f"{label}: new marker missing")

    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_bytes(result)
    print(f"patched {source} -> {output}")
    print(f"sha256={sha256(result)}")


def main() -> None:
    if len(sys.argv) != 3:
        print(f"usage: {sys.argv[0]} PREVIEW2_CORE OUTPUT_CORE", file=sys.stderr)
        raise SystemExit(2)
    patch(Path(sys.argv[1]), Path(sys.argv[2]))


if __name__ == "__main__":
    main()
