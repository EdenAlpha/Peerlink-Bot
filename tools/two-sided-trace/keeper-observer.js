'use strict';
// Experimental, read-only two-sided trace for the side-0 goalkeeper on 11.1.1.
// It observes checksum inputs, not account identifiers or network contents.
const lib = Process.findModuleByName('libUE4.so');
if (lib === null) throw new Error('libUE4.so is not loaded');
const base = lib.base;
const at = off => base.add(off);
const active = new Map();
function currentFrame() {
  try {
    const service = at(0x9a6a020).readPointer();
    if (service.isNull() || !service.readPointer().equals(at(0x98ae9e0))) return 0;
    const inner = service.add(8).readPointer();
    if (inner.isNull()) return 0;
    const controller = inner.add(0xa0).readPointer();
    if (controller.isNull() || !controller.readPointer().equals(at(0x98aedd0))) return 0;
    return controller.add(0x30).readU32();
  } catch (_) { return 0; }
}
function keeperPosition() {
  try {
    const p = at(0xa5909a0 + 0x170).readPointer();
    if (p.isNull()) return null;
    return [0x5dc, 0x5e0, 0x5e4].map(n => { const v=p.add(n).readFloat(); return Number.isFinite(v) ? v : null; });
  } catch (_) { return null; }
}
const ranges = {2: [0x6b00794,0x6b00908], 3: [0x6b00908,0x6b00aac]};
for (const part of [2,3]) {
  Interceptor.attach(at(ranges[part][0]), {
    onEnter(args) {
      if (args[3].toUInt32() !== 0 || args[4].toUInt32() !== 0) return;
      const frame = currentFrame();
      if (!frame) return;
      const sum = args[1];
      if (sum.isNull()) return;
      this.record = {part, frame, position: keeperPosition(), before: sum.readU32(), sum, terms: []};
      active.set(this.threadId, this.record);
    },
    onLeave() {
      if (!this.record || active.get(this.threadId) !== this.record) return;
      active.delete(this.threadId);
      try {
        const record = this.record;
        const total = (record.sum.readU32() - record.before) >>> 0;
        send({type:'keeper_component',frame:record.frame,part:record.part,
          position:record.position,total,terms:record.terms});
      } catch (e) { send({type:'observer_error',site:'component',error:String(e)}); }
    }
  });
}
for (const [name, offset, sumArg] of [['integer',0x6b00ad4,1],['float',0x6b00b20,0],['vector',0x6b00d20,1]]) {
  Interceptor.attach(at(offset), {
    onEnter(args) {
      const record = active.get(this.threadId);
      if (!record) return;
      const ret = this.returnAddress.sub(base).toUInt32();
      const range = ranges[record.part];
      if (ret < range[0] || ret >= range[1]) return;
      const sum = args[sumArg];
      if (sum.isNull()) return;
      this.record = record;
      this.sum = sum;
      this.before = sum.readU32();
      this.term = {call:ret - 4,kind:name};
      if (name === 'integer') this.term.value = args[0].toUInt32();
      if (name === 'vector') {
        try { this.term.value = [0,4,8].map(n => { const v=args[0].add(n).readFloat(); return Number.isFinite(v) ? v : null; }); }
        catch (_) { this.term.value = null; }
      }
    },
    onLeave() {
      if (!this.record || active.get(this.threadId) !== this.record) return;
      try {
        this.term.delta = (this.sum.readU32() - this.before) >>> 0;
        this.record.terms.push(this.term);
      } catch (e) { send({type:'observer_error',site:'helper',error:String(e)}); }
    }
  });
}
send({type:'keeper_observer_ready',engine:'11.1.1',parts:[2,3]});
