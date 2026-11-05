"""Checks that an APK's native libraries support 16 KB memory pages, which Google Play requires
for apps targeting Android 15 or newer: every ELF LOAD segment aligned to at least 16 KB, and
uncompressed .so files stored at 16 KB-aligned offsets in the APK.

    python ci/check_16kb.py app/build/outputs/apk/release/app-release-unsigned.apk
"""
import struct
import sys
import zipfile

PAGE = 16 * 1024


def load_alignments(elf: bytes) -> list[int]:
    if elf[:4] != b"\x7fELF":
        raise ValueError("not an ELF file")
    is64 = elf[4] == 2
    endian = "<" if elf[5] == 1 else ">"
    if is64:
        phoff, = struct.unpack_from(endian + "Q", elf, 0x20)
        phentsize, phnum = struct.unpack_from(endian + "HH", elf, 0x36)
    else:
        phoff, = struct.unpack_from(endian + "I", elf, 0x1C)
        phentsize, phnum = struct.unpack_from(endian + "HH", elf, 0x2A)
    aligns = []
    for i in range(phnum):
        base = phoff + i * phentsize
        p_type, = struct.unpack_from(endian + "I", elf, base)
        if p_type != 1:  # PT_LOAD
            continue
        p_align, = struct.unpack_from(endian + ("Q" if is64 else "I"), elf, base + (0x30 if is64 else 0x1C))
        aligns.append(p_align)
    return aligns


def main(path: str) -> int:
    problems, checked = [], 0
    with zipfile.ZipFile(path) as apk, open(path, "rb") as raw:
        for info in apk.infolist():
            if not (info.filename.startswith("lib/") and info.filename.endswith(".so")):
                continue
            abi = info.filename.split("/")[1]
            if abi not in ("arm64-v8a", "x86_64"):
                continue  # 16 KB pages only exist on 64-bit devices
            checked += 1
            aligns = load_alignments(apk.read(info))
            if min(aligns) < PAGE:
                problems.append(f"{info.filename}: LOAD segments aligned to {min(aligns)} bytes")
            if info.compress_type == zipfile.ZIP_STORED:
                raw.seek(info.header_offset)
                header = raw.read(30)
                name_len, extra_len = struct.unpack_from("<HH", header, 26)
                data_offset = info.header_offset + 30 + name_len + extra_len
                if data_offset % PAGE:
                    problems.append(f"{info.filename}: stored at offset {data_offset}, not 16 KB aligned")
    for p in problems:
        print("FAIL", p)
    print(f"Checked {checked} native libraries: " + ("all support 16 KB pages" if not problems else f"{len(problems)} problem(s)"))
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1]))
