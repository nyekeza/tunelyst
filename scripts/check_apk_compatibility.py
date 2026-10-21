#!/usr/bin/env python3
"""Check APK ABI presence and 16 KiB ZIP/ELF alignment for release validation."""

from __future__ import annotations

import argparse
import struct
import sys
import zipfile
from pathlib import Path

PAGE_SIZE = 16_384
REQUIRED_ABIS = {"armeabi-v7a", "arm64-v8a"}
REQUIRED_NATIVE_LIBS = {"libtaglib.so"}


def elf_load_segments(data: bytes, name: str) -> list[tuple[int, int]]:
    if len(data) < 16 or data[:4] != b"\x7fELF":
        raise ValueError(f"{name}: not a valid ELF shared object")

    elf_class = data[4]
    byte_order = data[5]
    if elf_class not in (1, 2) or byte_order not in (1, 2):
        raise ValueError(f"{name}: unsupported ELF class or byte order")
    endian = "<" if byte_order == 1 else ">"

    if elf_class == 2:
        header_format = endian + "HHIQQQIHHHHHH"
        program_header_format = endian + "IIQQQQQQ"
    else:
        header_format = endian + "HHIIIIIHHHHHH"
        program_header_format = endian + "IIIIIIII"

    header_size = struct.calcsize(header_format)
    if len(data) < 16 + header_size:
        raise ValueError(f"{name}: truncated ELF header")
    header = struct.unpack_from(header_format, data, 16)
    program_header_offset = header[4]
    program_header_entry_size = header[8]
    program_header_count = header[9]
    expected_entry_size = struct.calcsize(program_header_format)
    if program_header_entry_size < expected_entry_size:
        raise ValueError(f"{name}: malformed ELF program-header size")

    segments: list[tuple[int, int]] = []
    for index in range(program_header_count):
        entry_offset = program_header_offset + index * program_header_entry_size
        if entry_offset + expected_entry_size > len(data):
            raise ValueError(f"{name}: truncated ELF program headers")
        entry = struct.unpack_from(program_header_format, data, entry_offset)
        if elf_class == 2:
            segment_type, _, file_offset, virtual_address, _, _, _, alignment = entry
        else:
            segment_type, file_offset, virtual_address, _, _, _, _, alignment = entry
        if segment_type == 1:  # PT_LOAD
            segments.append((alignment, (virtual_address - file_offset) % PAGE_SIZE))

    if not segments:
        raise ValueError(f"{name}: no PT_LOAD segments found")
    return segments


def local_data_offset(archive: zipfile.ZipFile, info: zipfile.ZipInfo) -> int:
    # ZipInfo.header_offset points to the local header; read fields directly so
    # the check uses the actual data offset rather than the central-directory copy.
    fp = archive.fp
    if fp is None:
        raise ValueError("APK archive is already closed")
    fp.seek(info.header_offset)
    header = fp.read(30)
    if len(header) != 30:
        raise ValueError(f"{info.filename}: truncated ZIP local header")
    fields = struct.unpack("<IHHHHHIIIHH", header)
    if fields[0] != 0x04034B50:
        raise ValueError(f"{info.filename}: invalid ZIP local-header signature")
    file_name_length, extra_length = fields[-2], fields[-1]
    return info.header_offset + 30 + file_name_length + extra_length


def check_apk(apk_path: Path) -> None:
    if not apk_path.is_file():
        raise FileNotFoundError(f"APK does not exist: {apk_path}")

    with zipfile.ZipFile(apk_path) as archive:
        native_entries = [
            info for info in archive.infolist()
            if info.filename.startswith("lib/") and info.filename.endswith(".so")
        ]
        if not native_entries:
            raise ValueError("APK contains no packaged JNI libraries")

        abis = {info.filename.split("/", 2)[1] for info in native_entries}
        missing_abis = REQUIRED_ABIS - abis
        if missing_abis:
            raise ValueError(f"APK is missing required ARM ABIs: {', '.join(sorted(missing_abis))}")

        for abi in REQUIRED_ABIS:
            abi_libs = {Path(info.filename).name for info in native_entries if info.filename.startswith(f"lib/{abi}/")}
            missing_libs = REQUIRED_NATIVE_LIBS - abi_libs
            if missing_libs:
                raise ValueError(f"APK ABI {abi} is missing native libraries: {', '.join(sorted(missing_libs))}")

        for info in native_entries:
            offset = local_data_offset(archive, info)
            if info.compress_type != zipfile.ZIP_STORED:
                raise ValueError(f"{info.filename}: JNI library is compressed; expected an uncompressed aligned entry")
            if offset % PAGE_SIZE:
                raise ValueError(f"{info.filename}: ZIP data offset {offset} is not 16 KiB aligned")

            abi = info.filename.split("/", 2)[1]
            if abi in {"arm64-v8a", "x86_64"}:
                segments = elf_load_segments(archive.read(info), info.filename)
                for alignment, congruence in segments:
                    if alignment < PAGE_SIZE or congruence != 0:
                        raise ValueError(
                            f"{info.filename}: PT_LOAD alignment/congruence is incompatible with 16 KiB pages "
                            f"(p_align=0x{alignment:x}, vaddr-offset mod 16 KiB={congruence})"
                        )

    print(f"PASS: {apk_path} includes ARMv7 and ARM64 JNI libraries with 16 KiB ZIP alignment")
    print("PASS: all packaged 64-bit PT_LOAD segments declare 16 KiB-compatible alignment")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path, help="APK to inspect (debug or production-signed release)")
    args = parser.parse_args()
    try:
        check_apk(args.apk)
    except (OSError, ValueError, zipfile.BadZipFile, struct.error) as error:
        print(f"FAIL: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
