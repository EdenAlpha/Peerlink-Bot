"""Exercise the original 11.1.1 match coordinator handoff with stand-in native services.

Requires unicorn and pyelftools. Pass the verified libUE4.so as the only argument.
"""
import hashlib
from pathlib import Path
from struct import pack, unpack
import sys

from elftools.elf.elffile import ELFFile
from unicorn import UC_ARCH_ARM64, UC_HOOK_CODE, UC_MODE_ARM, Uc
from unicorn.arm64_const import (
    UC_ARM64_REG_PC,
    UC_ARM64_REG_SP,
    UC_ARM64_REG_TPIDR_EL0,
    UC_ARM64_REG_X0,
    UC_ARM64_REG_X1,
    UC_ARM64_REG_X30,
)

ENGINE_SHA = "2cea0c85344d7d7f295aea1c2353621f02db78dbccd888a0a21a0508ac330659"
COORDINATOR_TICK = 0x7B2A638
DATA = 0x100000
COORDINATOR, GAME, SETTINGS, STACK, TLS, STOP = (DATA + x for x in (0, 0x200, 0x400, 0x1000, 0x3000, 0x4000))
PLATFORM, PHASE_QUEUE, MATCH_CHECKS, RESULT_WATCHER, ADVICE = (DATA + x for x in (0x500, 0x600, 0x700, 0x800, 0x900))


class Handoff:
    def __init__(self, code, jump_table, *, watch_result, advice, delay_task_present):
        self.uc = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
        self.calls = []
        self.attachments = []
        self.uc.mem_map(DATA, 0x5000)
        self.uc.mem_write(GAME + 0x20, pack("<Q", SETTINGS))
        self.uc.mem_write(GAME + 0x168, pack("<Q", SETTINGS))
        self.uc.mem_write(COORDINATOR + 0xA9, bytes([watch_result]))
        allocate = {0x1D0: PLATFORM, 0x80: PHASE_QUEUE, 0xB8: RESULT_WATCHER}
        stubs = {
            0x7A5DF70: ("setup_log", lambda *_: 0),
            0x7A87DB8: ("game", lambda user, _: GAME if user == 1 else 0),
            0x7A587CC: ("settings", lambda address, _: address),
            0x7A5C04C: ("apply_settings", lambda address, _: int(address == SETTINGS)),
            0x8C1BBD0: ("allocate", lambda size, _: allocate[size]),
            0x7B2BDFC: ("platform_init", lambda *_: 0),
            0x7B2F1A8: ("phase_init", lambda *_: 0),
            0x7B290F0: ("match_checks", lambda *_: MATCH_CHECKS),
            0x7B4AC44: ("result_init", lambda *_: 0),
            0x7A8CEE0: ("advice_enabled", lambda address, _: int(address == SETTINGS and advice)),
            0x7B27250: ("advice", lambda *_: ADVICE),
            0x7E97E8C: ("network_delay_task", lambda *_: int(delay_task_present)),
            0x7E97E98: ("initialize_network_delay", lambda *_: 0),
            0x7BC8F44: ("active_update", lambda *_: 0),
            0x7E19FDC: ("sample_network", lambda *_: 0),
            0x822B6D0: ("attach", self.attach),
        }
        pages = {0xCA0000, *(address & ~0xFFF for address in stubs)}
        pages.update(range(COORDINATOR_TICK & ~0xFFF, (COORDINATOR_TICK + len(code) + 0xFFF) & ~0xFFF, 0x1000))
        for page in pages:
            self.uc.mem_map(page, 0x1000)
        self.uc.mem_write(COORDINATOR_TICK, code)
        self.uc.mem_write(0xCA00DC, jump_table)
        for address, (name, function) in stubs.items():
            self.uc.hook_add(UC_HOOK_CODE, self.stub(name, function), begin=address, end=address)

    def attach(self, parent, child):
        assert parent == COORDINATOR
        self.attachments.append(child)
        return 0

    def stub(self, name, function):
        def call(uc, _address, _size, _user):
            self.calls.append(name)
            result = function(uc.reg_read(UC_ARM64_REG_X0), uc.reg_read(UC_ARM64_REG_X1))
            uc.reg_write(UC_ARM64_REG_X0, result)
            uc.reg_write(UC_ARM64_REG_PC, uc.reg_read(UC_ARM64_REG_X30))
        return call

    def tick(self, state):
        self.uc.mem_write(COORDINATOR + 0xAC, pack("<I", state))
        self.uc.reg_write(UC_ARM64_REG_X0, COORDINATOR)
        self.uc.reg_write(UC_ARM64_REG_SP, STACK + 0x1000)
        self.uc.reg_write(UC_ARM64_REG_TPIDR_EL0, TLS)
        self.uc.reg_write(UC_ARM64_REG_X30, STOP)
        self.uc.emu_start(COORDINATOR_TICK, STOP, count=300)
        assert self.uc.reg_read(UC_ARM64_REG_PC) == STOP
        return unpack("<I", self.uc.mem_read(COORDINATOR + 0xAC, 4))[0]


def main(path):
    with path.open("rb") as source:
        assert hashlib.file_digest(source, "sha256").hexdigest() == ENGINE_SHA
        source.seek(0)
        elf = ELFFile(source)

        def read(address, size):
            for segment in elf.iter_segments():
                start = segment["p_vaddr"]
                if segment["p_type"] == "PT_LOAD" and start <= address < start + segment["p_filesz"]:
                    source.seek(segment["p_offset"] + address - start)
                    return source.read(size)
            raise ValueError(f"Unmapped address {address:#x}")

        code = read(COORDINATOR_TICK, 2496)
        jump_table = read(0xCA00DC, 24)
    for watch_result in (False, True):
        for optional in (False, True):
            fixture = Handoff(code, jump_table, watch_result=watch_result, advice=optional, delay_task_present=optional)
            assert fixture.tick(0) == 0
            assert fixture.calls == [] and fixture.attachments == []
            assert fixture.tick(7) == 8
            expected = [PLATFORM, PHASE_QUEUE, MATCH_CHECKS]
            if watch_result:
                expected.append(RESULT_WATCHER)
            if optional:
                expected.append(ADVICE)
            assert fixture.attachments == expected
            for offset, task in ((0x80, PHASE_QUEUE), (0x88, RESULT_WATCHER if watch_result else 0),
                                 (0x90, PLATFORM), (0x98, MATCH_CHECKS), (0xA0, ADVICE if optional else 0)):
                assert unpack("<Q", fixture.uc.mem_read(COORDINATOR + offset, 8))[0] == task
            assert ("initialize_network_delay" in fixture.calls) == (not optional)
            assert fixture.calls[-2:] == ["active_update", "sample_network"]
            fixture.calls.clear()
            assert fixture.tick(8) == 8
            assert fixture.calls == ["active_update", "sample_network"] and fixture.attachments == expected
    print("Original coordinator advanced to active, attached tasks in stock order, and did not recreate them.")


if __name__ == "__main__":
    main(Path(sys.argv[1]))
