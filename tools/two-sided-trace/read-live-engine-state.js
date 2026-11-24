'use strict';
const module = Process.findModuleByName('libUE4.so');
if (module === null) throw new Error('libUE4.so not loaded');
const base = module.base;
const p = off => base.add(off);

function sample() {
  const result = {};
  try {
    const flow = p(0xa53b6e8).readPointer();
    if (!flow.isNull()) {
      result.flowState = flow.add(0x120).readU32();
      const task = flow.add(0x190).readPointer();
      if (!task.isNull()) {
        const first = task.add(0x70).readPointer();
        const end = task.add(0x78).readPointer();
        const bytes = end.sub(first).toInt32();
        if (!first.isNull() && bytes > 0 && bytes % 0xe0 === 0 && bytes / 0xe0 <= 32) {
          const stack = [];
          for (let i = 0; i < bytes / 0xe0; i++) {
            const leaf = first.add(i * 0xe0);
            const tag = leaf.readU8();
            const len = (tag & 1) ? leaf.add(8).readU64().toNumber() : tag >>> 1;
            const str = (tag & 1) ? leaf.add(16).readPointer() : leaf.add(1);
            if (len > 0 && len <= 160 && !str.isNull()) {
              const name = str.readUtf8String(len);
              if (/^[A-Za-z0-9_\/]+$/.test(name)) stack.push({name, complete: leaf.add(0x50).readU8()});
            }
          }
          result.sceneStack = stack;
        }
      }
    }
  } catch (e) { result.flowReadError = String(e); }
  try {
    result.tickGate = p(0xa541870).readU8();
    const service = p(0x9a6a020).readPointer();
    if (!service.isNull() && service.readPointer().equals(p(0x98ae9e0))) {
      const inner = service.add(8).readPointer();
      if (!inner.isNull()) {
        result.failureLatched = inner.add(0x12).readU8();
        const controller = inner.add(0xa0).readPointer();
        if (!controller.isNull() && controller.readPointer().equals(p(0x98aedd0))) {
          result.commandFrame = controller.add(0x30).readU32();
          result.missingMask = controller.add(0x28).readU32();
          result.aheadMask = controller.add(0x2c).readU32();
          result.stopPending = controller.add(0x45).readU8();
          result.stopped = controller.add(0x49).readU8();
        }
      }
    }
  } catch (e) { result.simulationReadError = String(e); }
  try {
    const match = p(0xa533ec0).readPointer();
    if (!match.isNull()) {
      result.matchOuter = match.add(0x138).readU8();
      const core = match.add(0x2f8).readPointer();
      if (!core.isNull() && core.readPointer().equals(p(0x9899d50))) result.matchCore = core.add(8).readU32();
    }
  } catch (e) { result.matchReadError = String(e); }
  send(result);
}
sample(); setTimeout(sample, 1000); setTimeout(sample, 2000);
