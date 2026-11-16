"""Verify the original checksum object's pointer path and counter side effect.

Usage: python tests/native/verify_match_state_checksum.py /path/to/libUE4.so
Requires unicorn and pyelftools. The proprietary library is not included.
"""
import hashlib
from pathlib import Path
import struct
import sys

from elftools.elf.elffile import ELFFile
from unicorn import Uc, UC_ARCH_ARM64, UC_MODE_ARM, UC_HOOK_CODE
from unicorn.arm64_const import (
    UC_ARM64_REG_X0, UC_ARM64_REG_X1, UC_ARM64_REG_X2,
    UC_ARM64_REG_X30, UC_ARM64_REG_SP, UC_ARM64_REG_PC,
)

EXPECTED_SHA256 = "2cea0c85344d7d7f295aea1c2353621f02db78dbccd888a0a21a0508ac330659"
ENTRY, SUM_ENTRY, MAP = 0x7053D8C, 0x6AFFF20, 0xA5909A0
HANDLE, STATE, FRAME = 0x4000000, 0x4001000, 0x4002000
STACK, STOP = 0x2000000, 0x5000000


def original_pages(path):
    with path.open("rb") as file:
        assert hashlib.file_digest(file, "sha256").hexdigest() == EXPECTED_SHA256
        file.seek(0)
        elf = ELFFile(file)
        result = {}
        for page in (0x7053000, 0x6AFF000, 0x6B01000):
            segment = next(s for s in elf.iter_segments() if s["p_type"] == "PT_LOAD"
                           and s["p_vaddr"] <= page < s["p_vaddr"] + s["p_filesz"])
            file.seek(segment["p_offset"] + page - segment["p_vaddr"])
            result[page] = file.read(0x1000)
        return result


def verify(path):
    pages = original_pages(path)
    for checksum_sum in (0, 0x12345678, 0xFFFFFFFF):
        machine = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
        for page, code in pages.items():
            machine.mem_map(page, 0x1000)
            machine.mem_write(page, code)
        for page, size in ((MAP & ~0xFFF, 0x1000), (HANDLE, 0x3000), (STACK, 0x2000), (STOP, 0x1000)):
            machine.mem_map(page, size)
        machine.mem_write(MAP, struct.pack("<Q", HANDLE))
        machine.mem_write(HANDLE, struct.pack("<Q", STATE))
        machine.mem_write(STATE, struct.pack("<I", 55))
        calls = []

        def inspect_sum(uc, address, size, user):
            del address, size, user
            assert uc.reg_read(UC_ARM64_REG_X0) == STATE
            assert uc.reg_read(UC_ARM64_REG_X1) == FRAME
            assert struct.unpack("<I", uc.mem_read(STATE, 4))[0] == 56
            calls.append(True)
            uc.mem_write(FRAME, struct.pack("<I", 27))
            uc.reg_write(UC_ARM64_REG_X0, checksum_sum)
            uc.reg_write(UC_ARM64_REG_PC, uc.reg_read(UC_ARM64_REG_X30))

        machine.hook_add(UC_HOOK_CODE, inspect_sum, begin=SUM_ENTRY, end=SUM_ENTRY)
        machine.reg_write(UC_ARM64_REG_X2, FRAME)
        machine.reg_write(UC_ARM64_REG_X30, STOP)
        machine.reg_write(UC_ARM64_REG_SP, STACK + 0x1000)
        machine.emu_start(ENTRY, STOP, count=100)
        assert machine.reg_read(UC_ARM64_REG_PC) == STOP
        assert calls == [True]
        assert machine.reg_read(UC_ARM64_REG_X0) == (checksum_sum + (checksum_sum >> 16)) & 0xFFFFFFFF
        assert struct.unpack("<I", machine.mem_read(FRAME, 4))[0] == 27
    print("ORIGINAL_CHECKSUM_DOUBLE_INDIRECTION_COUNTER_AND_FOLD_VERIFIED")


if __name__ == "__main__":
    verify(Path(sys.argv[1]))
