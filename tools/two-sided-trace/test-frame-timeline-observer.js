'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname, 'scene-target-observer.js'), 'utf8');
const base = 0x100000000, words = new Map(), events = [], hooks = new Map();
class Pointer {
  constructor(value) { this.value = value; }
  add(offset) { return new Pointer(this.value + offset); }
  equals(other) { return this.value === other.value; }
  isNull() { return this.value === 0; }
  readU32() { return (words.get(this.value) ?? 0) >>> 0; }
  readU8() { return this.readU32() & 255; }
  readPointer() { return new Pointer(words.get(this.value) ?? 0); }
}
const p = value => new Pointer(value), put = (address, value) => words.set(address, value);
for (const [offset, word] of [[0x6fe89b8, 0xd101c3ff], [0x6fe7494, 0xa9bd7bfd],
 [0x725b0d0, 0xa9be7bfd], [0x725b114, 0x360000a0], [0x725b134, 0xfc1b0fe8]]) put(base + offset, word);
put(base + 0x9a6a020, 0x200000); put(0x200000, base + 0x98ae9e0);
put(0x200008, 0x210000); put(0x2100a0, 0x220000); put(0x220000, base + 0x98aedd0);
for (let i = 0; i < 22; i++) {
  const player = 0x300000 + i * 0x1000;
  put(base + 0xa5909a0 + 0x170 + i * 8, player);
  put(player + 0x5dc, i + 123);
}
vm.runInNewContext(source, {
  Process: {arch: 'arm64', findModuleByName: () => ({base: p(base)})},
  Interceptor: {attach: (pointer, callbacks) => hooks.set(pointer.value - base, callbacks)},
  send: event => events.push(JSON.parse(JSON.stringify(event))),
});
assert.equal(events[0].type, 'scene_target_observer_ready');
assert.equal(events[0].frameTimeline, 1);
const update = (frame, player) => {
  put(0x220030, frame);
  hooks.get(0x725b0d0).onEnter([p(player)]);
};
update(10, 0x301000); // another player must not create an inconsistent snapshot
update(10, 0x300000); update(10, 0x300000); // one sample per observed frame
update(11, 0x300000); update(14, 0x300000); update(5, 0x300000);
const frames = events.filter(event => event.type === 'frame_state');
assert.deepEqual(frames.map(event => event.frame), [10, 11, 14, 5]);
assert.equal(frames[0].positions.length, 22);
assert.equal(frames[0].positions[0][0], 123);
assert.equal(frames[0].positions[21][0], 144);
assert.equal(frames[1].skippedFrames, 0);
assert.equal(frames[2].skippedFrames, 2);
assert.equal(frames[3].rewound, true);
assert.equal(frames[0].ballBits, null);
assert(!events.some(event => event.type === 'frame_observer_error'));
console.log('Frame timeline fixture passed: anchor, 22 players, duplicates, gaps and rewind.');
