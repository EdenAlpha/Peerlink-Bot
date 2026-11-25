'use strict';
// Verify checksum caller selection without invoking native game functions.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const base = 0x100000000, hooks = new Map(), events = [];
class Pointer {
 constructor(value) { this.value = value; }
 add(n) { return new Pointer(this.value + n); }
 equals(other) { return this.value === other.value; }
}
const p = n => new Pointer(n);
vm.runInNewContext(fs.readFileSync(path.join(__dirname, 'fullgame-observer.js'), 'utf8'), {
 Process: {
  findModuleByName: () => ({base: p(base)}),
  findModuleByAddress: address => address.value === 0x200000 ? {name: 'libpeerlinkbackend.so'} : null,
 },
 NativeFunction: function () { return () => 0; },
 Interceptor: {attach: (address, callbacks) => hooks.set(address.value - base, callbacks)},
 send: event => events.push(event),
});
const hook = hooks.get(0x7053d8c);
function selected(caller) {
 const state = {returnAddress: p(caller)};
 hook.onEnter.call(state, [null, null, p(123)]);
 return state.wanted;
}
assert.equal(selected(base + 0x7053bbc), true);
assert.equal(selected(0x200000), true);
assert.equal(selected(base + 0x7053bc0), false);
assert(events.some(event => event.type === 'observer_ready'));
console.log('Checksum observer caller fixture passed for game, bot host and unrelated callers.');
