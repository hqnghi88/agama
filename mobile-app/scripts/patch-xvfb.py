#!/usr/bin/env python3
"""Apply the verified Xvfb/Xtigervnc fixes for running under PRoot on Android.

Xvfb's path is verified byte-for-byte before writing; known inputs are idempotent.
Fixes applied (from the working device rootfs):
  1. AArch64 conditional branch NOP'd (bypasses the failing in-process xkbcomp path).
  2. A 12-byte equal-length string swap '%s%sxkbcomp"' -> 'true"   %s%s' so the
     server no longer spawns xkbcomp for keymap compile (keymap is pre-placed at
     /var/lib/xkb/server-0.xkm by startup.sh instead).

Usage: patch-xvfb.py [--dry-run] <path-to-Xvfb> [<path-to-Xtigervnc>]
"""

import hashlib
import shutil
import sys

HUNKS = {
    # sha256 of pristine Debian binaries -> hunks (offset, orig bytes, new bytes)
    "5ca4be733f4b4599f684563771f87d4672f1caf9a06b55613ec5dff66fec8bd9": [  # Xvfb
        (0x114244, "00030035", "1f2003d5"),
        (0x1A16B1, "25732573786b62636f6d7022", "747275652220202025732573"),
    ],
    "03edbfcf91d06ddc7473f4bada1d2bf16a20d6257735ca22bec0fdb7368ce584": [  # Xtigervnc
        (0x14D118, "20030035", "1f2003d5"),
        (0x239339, "25732573786b62636f6d7022", "747275652220202025732573"),
    ],
}

PATCHED = {
    "62f2915e0dae3b602d4933ae1402f1c4f1144f8b71bf365dc9ad52da7ff1edc2",  # Xvfb nop
    "baafda4e13c6b24e3781c2c85a658ef0165c055998be085e3205612e7676ccd2",  # Xtigervnc nop
}


def patch(path: str, dry_run: bool) -> bool:
    if not path:
        return False
    try:
        data = bytearray(open(path, "rb").read())
    except OSError as e:
        print(f"[patch] {path}: skipped ({e})", file=sys.stderr)
        return False

    digest = hashlib.sha256(data).hexdigest()
    if digest in PATCHED:
        print(f"[patch] {path}: already patched, skipping")
        return True
    hunks = HUNKS.get(digest)
    if not hunks:
        print(f"[patch] {path}: unrecognized binary (sha {digest[:12]}), skipping", file=sys.stderr)
        return False

    for off, orig, new in hunks:
        o = bytes.fromhex(orig)
        n = bytes.fromhex(new)
        if data[off:off + len(o)] != o:
            print(f"[patch] {path}: mismatch at 0x{off:x}, not patching", file=sys.stderr)
            return False
        data[off:off + len(o)] = n

    if dry_run:
        print(f"[patch] {path}: verified ({digest[:12]} -> {hashlib.sha256(data).hexdigest()[:12]})")
        return True

    backup = f"{path}.pre"
    shutil.copy2(path, backup)
    open(path, "wb").write(data)
    print(f"[patch] {path}: patched, backup at {backup}")
    return True


def main() -> int:
    argv = sys.argv[1:]
    dry_run = "--dry-run" in argv
    if dry_run:
        argv.remove("--dry-run")
    ok = True
    for p in argv:
        ok = patch(p, dry_run) and ok
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())