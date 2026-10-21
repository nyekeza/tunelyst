#!/usr/bin/env python3
"""Check ARM ABI presence and 16 KiB-compatible 64-bit ELF libraries in an AAB."""

from __future__ import annotations

import struct
import sys
import zipfile
from pathlib import Path

from check_apk_compatibility import PAGE_SIZE, elf_load_segments

REQUIRED_ABIS = {"armeabi-v7a", "arm64-v8a"}
REQUIRED_LIBRARIES = {"libtaglib.so"}


def check_aab(bundle_path: Path) -> None:
    if not bundle_path.is_file():
        raise FileNotFoundError(f"AAB does not exist: {bundle_path}")

    with zipfile.ZipFile(bundle_path) as bundle:
        native_entries = [
            info for info in bundle.infolist()
            if info.filename.startswith("base/lib/") and info.filename.endswith(".so")
        ]
        if not native_entries:
            raise ValueError("AAB contains no base-module JNI libraries")

        abis = {info.filename.split("/", 3)[2] for info in native_entries}
        missing_abis = REQUIRED_ABIS - abis
        if missing_abis:
            raise ValueError(f"AAB is missing required ARM ABIs: {', '.join(sorted(missing_abis))}")

        for abi in REQUIRED_ABIS:
            libraries = {
                Path(info.filename).name
                for info in native_entries
                if info.filename.startswith(f"base/lib/{abi}/")
            }
            missing_libraries = REQUIRED_LIBRARIES - libraries
            if missing_libraries:
                raise ValueError(f"AAB ABI {abi} is missing: {', '.join(sorted(missing_libraries))}")

        for info in native_entries:
            abi = info.filename.split("/", 3)[2]
            if abi not in {"arm64-v8a", "x86_64"}:
                continue
            for alignment, congruence in elf_load_segments(bundle.read(info), info.filename):
                if alignment < PAGE_SIZE or congruence != 0:
                    raise ValueError(
                        f"{info.filename}: 64-bit PT_LOAD is not 16 KiB-compatible "
                        f"(p_align=0x{alignment:x}, vaddr-offset mod 16 KiB={congruence})"
                    )

    print(f"PASS: {bundle_path} contains ARMv7 and ARM64 JNI libraries")
    print("PASS: all packaged 64-bit PT_LOAD segments declare 16 KiB-compatible alignment")


def main() -> int:
    if len(sys.argv) != 2:
        print(f"Usage: {Path(sys.argv[0]).name} <app-release.aab>", file=sys.stderr)
        return 2
    try:
        check_aab(Path(sys.argv[1]))
    except (OSError, ValueError, struct.error, zipfile.BadZipFile) as error:
        print(f"FAIL: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
