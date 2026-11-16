"""Compare the original and headless mesh-wait gates against the owned 11.1.1 binary.

Usage: python tests/native/verify_headless_mesh_wait.py /path/to/libUE4.so
Requires unicorn and pyelftools; the game binary is not included in this repository.
"""
from itertools import product
from pathlib import Path
import hashlib
import struct
import sys

from elftools.elf.elffile import ELFFile
from unicorn import UC_ARCH_ARM64, UC_HOOK_CODE, UC_MODE_ARM, Uc
from unicorn.arm64_const import UC_ARM64_REG_PC, UC_ARM64_REG_SP, UC_ARM64_REG_X0, UC_ARM64_REG_X30

EXPECTED_SHA256 = "2cea0c85344d7d7f295aea1c2353621f02db78dbccd888a0a21a0508ac330659"
START = 0x39D0688
HELPER = 0x3949EA4
STOP = 0x39D1000
ACTOR = 0x1000000
STACK = 0x2000000


def original_code(path):
    with path.open("rb") as file:
        digest = hashlib.file_digest(file, "sha256").hexdigest()
        if digest != EXPECTED_SHA256:
            raise ValueError("The supplied engine is not the verified 11.1.1 build")
        file.seek(0)
        elf = ELFFile(file)
        for segment in elf.iter_segments():
            if segment["p_type"] == "PT_LOAD" and segment["p_vaddr"] <= START < segment["p_vaddr"] + segment["p_filesz"]:
                file.seek(segment["p_offset"] + START - segment["p_vaddr"])
                data = file.read(0x98)
                if len(data) != 0x98 or data[0x6C:0x70] != bytes.fromhex("69000034"):
                    raise ValueError("The original readiness instruction does not match")
                return data
    raise ValueError("The original readiness function was not found")


class Machine:
    def __init__(self, code):
        self.uc = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
        for address, size in ((0x39D0000, 0x2000), (0x3949000, 0x1000), (ACTOR, 0x4000), (STACK, 0x2000)):
            self.uc.mem_map(address, size)
        self.uc.mem_write(START, code)
        self.helper = 0
        self.uc.hook_add(UC_HOOK_CODE, self._helper, begin=HELPER, end=HELPER)

    def _helper(self, uc, address, size, user):
        del address, size, user
        uc.reg_write(UC_ARM64_REG_X0, self.helper)
        uc.reg_write(UC_ARM64_REG_PC, uc.reg_read(UC_ARM64_REG_X30))

    def run(self, case):
        helper, blocked, stage_a, stage_b, stage_c, enabled_a, ready_a, enabled_b, ready_b = case
        self.helper = helper
        for offset, value in ((0x1A28, stage_a), (0x1A38, stage_b), (0x18D8, stage_c)):
            self.uc.mem_write(ACTOR + offset, struct.pack("<I", value))
        for offset, value in ((0x1A6B, blocked), (0x1A5E, enabled_a), (0x1A58, ready_a),
                              (0x1A5F, enabled_b), (0x1A59, ready_b)):
            self.uc.mem_write(ACTOR + offset, bytes([value]))
        self.uc.reg_write(UC_ARM64_REG_X0, ACTOR)
        self.uc.reg_write(UC_ARM64_REG_X30, STOP)
        self.uc.reg_write(UC_ARM64_REG_SP, STACK + 0x1000)
        self.uc.emu_start(START, STOP, count=200)
        assert self.uc.reg_read(UC_ARM64_REG_PC) == STOP
        return self.uc.reg_read(UC_ARM64_REG_X0)


def main(path):
    original = original_code(path)
    patched = original[:0x6C] + bytes.fromhex("03000014") + original[0x70:]
    old, new = Machine(original), Machine(patched)
    checked = changed = 0
    for case in product((0, 1), (0, 1), (0, 1, 4, 5), (0, 1, 4, 5), (0, 1, 5, 6),
                        (0, 1), (0, 1), (0, 1), (0, 1)):
        helper, blocked, a, b, c, enabled_a, ready_a, enabled_b, ready_b = case
        remaining_checks = bool(helper and not blocked and not 1 <= a <= 4 and not 1 <= b <= 4 and
                                not 1 <= c <= 5 and (not enabled_b or ready_b))
        before, after = old.run(case), new.run(case)
        assert before == int(remaining_checks and (not enabled_a or ready_a)), case
        assert after == int(remaining_checks), case
        changed += before != after
        checked += 1
    print(f"Checked {checked} readiness combinations; {changed} bypass only the missing mesh wait.")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    main(Path(sys.argv[1]))
