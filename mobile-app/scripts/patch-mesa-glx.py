#!/usr/bin/env python3
"""Apply the verified libGLX_mesa GLX fix for running JOGL/OpenGL under PRoot on Android.

Mesa's GLX swap-interval path crashes with a NULL callback deref (SIGSEGV at
pc=0x0 inside `glXQueryDrawable`) when JOGL creates a desktop GL context on the
software (llvmpipe) Xvfb/Xtigervnc server. GAMA's 3D views (JOGL renderer) would
otherwise abort the JVM in native code.

Trigger: shared-resource init calls setSwapInterval -> glXQueryDrawable/EXT ->
the drawable's vfunc table slot is NULL for swrast drawables.

Fix: NOP the three client entry points to safe stubs (mov w0,#0; ret), so JOGL
treats swap-interval (vsync) control as a no-op:
  - glXQueryDrawable    @ 0x29130
  - glXSwapIntervalEXT  @ 0x23200
  - glXSwapIntervalSGI  @ 0x241f0

The path is verified byte-for-byte via sha256 before writing; known inputs are
idempotent and unknown binaries are skipped.

Verified on emulator: GAMA gui experiment with `display type: 3D` renders.

Usage: patch-mesa-glx.py [--dry-run] <path-to-libGLX_mesa.so.0.0.0>
"""

import hashlib
import shutil
import sys

NOP_STUB = "00008052c0035fd6"  # mov w0, #0 ; ret

HUNKS = {
    # sha256 of pristine Ubuntu 24.04 libGLX_mesa.so.0.0.0 -> offsets
    "cc0f4c2ffb153a1727dbca42c78af2e7b6a3e9e82e0b3a7a941fe5133d8b4853": [
        0x29130,  # glXQueryDrawable
        0x23200,  # glXSwapIntervalEXT
        0x241F0,  # glXSwapIntervalSGI
    ],
}

PATCHED = {
    "802749733149f3cdd07fe87a05a6521cff454b2ce27fed05fadd9f045ef478dc",
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
    offsets = HUNKS.get(digest)
    if offsets is None:
        print(f"[patch] {path}: unrecognized binary (sha {digest[:12]}), skipping", file=sys.stderr)
        return False

    stub = bytes.fromhex(NOP_STUB)
    for off in offsets:
        data[off:off + len(stub)] = stub

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