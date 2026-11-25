'use strict';

const assert = require('node:assert/strict');
const {execFileSync} = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname, 'scene-target-observer.js'), 'utf8');
const base = 0x100000000;

function fixture({advance, assignKeeper = true, gateReached = true, supported = true, hostWrapper = false}) {
 const words = new Map();
 const hooks = new Map();
 const events = [];
 class Pointer {
  constructor(value) { this.value = value; }
  add(offset) { return new Pointer(this.value + offset); }
  sub(pointer) { return new Pointer(this.value - pointer.value); }
  compare(pointer) { return Math.sign(this.value - pointer.value); }
  isNull() { return this.value === 0; }
  equals(pointer) { return this.value === pointer.value; }
  toUInt32() { return this.value >>> 0; }
  readU32() { return (words.get(this.value) ?? 0) >>> 0; }
  readS32() { return this.readU32() | 0; }
  readU8() { return this.readU32() & 255; }
  readPointer() { return new Pointer(words.get(this.value) ?? 0); }
 }
 const p = value => new Pointer(value);
 const put = (address, value) => words.set(address, value);
 for (const [offset, word] of [
  [0x6fe89b8, 0xd101c3ff], [0x6fe7494, 0xa9bd7bfd], [0x725b0d0, 0xa9be7bfd],
  [0x725b114, 0x360000a0], [0x725b134, supported ? 0xfc1b0fe8 : 0],
 ]) put(base + offset, word);
 const module = {base: p(base), name: 'libUE4.so'};
 vm.runInNewContext(source, {
  Process: {arch: 'arm64', findModuleByName: () => module,
   findModuleByAddress: pointer => pointer.value < base ? {name: 'libpeerlinkbackend.so'} : module},
  Interceptor: {attach: (pointer, callbacks) => hooks.set(pointer.value - base, callbacks)},
  ptr: p,
  send: event => events.push(JSON.parse(JSON.stringify(event))),
 });

 const service = 0x200000, inner = 0x210000, command = 0x220000;
 put(base + 0x9a6a020, service);
 put(service, base + 0x98ae9e0);
 put(service + 8, inner);
 put(inner + 0xa0, command);
 put(command, base + 0x98aedd0);
 const frame = value => put(command + 0x30, value);

 const simulation = 0x230000, roster0 = 0x240000, roster1 = 0x250000;
 const context = 0x260000, scene = context + 0x3f0, actor = 0x270000;
 const sceneTransition = 0x280000, updateTransition = 0x290000;
 const keeper = 0x2a0000, controller = 0x2b0000, track = 0x2c0000, pose = 0x2d0000;
 put(base + 0xa58de28, simulation);
 put(simulation + 0x3030, roster0);
 put(simulation + 0x3080, roster1);
 put(simulation + 0x390, updateTransition);
 put(roster1 + 0x42b8, 7); // The second keeper is slot 1, not slot 0.
 put(scene + 4, 8);
 put(scene + 8, 197064);
 put(scene + 0x280, actor);
 put(actor, 0x300000);
 put(0x300000 + 0x28, base + 0x69d25bc);
 put(actor + 0xc0, 0x310000);
 put(actor + 0xc8, 0x310000 + 36); // Three native cast entries, twelve bytes each.
 put(base + 0xa5909a0 + 0x170, keeper);
 put(keeper + 0x798, 1);
 put(keeper + 0x7a8, controller);
 put(controller + 0xa88, track);
 put(track + 0x198, pose);

 function invoke(offset, caller, args, body = () => {}) {
  const state = {returnAddress: p(caller === 'host' ? 0x450000 : base + caller), threadId: 8};
  const hooksForRoutine = hooks.get(offset);
  hooksForRoutine.onEnter.call(state, args.map(p));
  body();
  hooksForRoutine.onLeave.call(state);
 }
 frame(3837);
 put(sceneTransition + 0xc, 3);
 invoke(0x6fe89b8, 0x6fe85e4, [scene, context, sceneTransition]);
 assert(!events.some(event => event.type === 'keeper_scene_prepare'));
 invoke(0x6fe89b8, 0x6fe85e8, [scene, context, sceneTransition], () => {
  put(sceneTransition + 0x20c, Number(assignKeeper));
 });
 frame(3840);
 invoke(0x6fe7494, hostWrapper ? 'host' : 0x6fe8634, [context, sceneTransition], () => {
  if (assignKeeper) put(sceneTransition + 0xc, 0);
 });
 frame(3842);
 put(updateTransition + 0xc, assignKeeper ? 0 : 3);
 put(updateTransition + 0x20c, Number(assignKeeper));
 put(updateTransition + 0x22e, 1);
 put(controller + 0xa83, Number(!advance));
 invoke(0x725b0d0, 0, [keeper], () => {
  if (!gateReached) return;
  hooks.get(0x725b114).call({threadId: 7, context: {x19: p(keeper), x0: p(!advance)}});
  hooks.get(0x725b114).call({threadId: 8, context: {x19: p(keeper), x0: p(Number(advance))}});
  if (advance) put(pose + 0x40, 0xc23e8000);
 });
 invoke(0x725b134, 0, [keeper], () => put(keeper + 0x5dc, words.get(pose + 0x40) ?? 0));
 frame(3900);
 invoke(0x725b0d0, 0, [keeper]);

 const prepared = events.find(event => event.type === 'keeper_scene_prepare');
 assert.equal(prepared.availableRoles, 3);
 assert.equal(prepared.after[0].override, assignKeeper);
 assert.equal(prepared.after[1].player, 12);
 assert(events.some(event => event.type === 'scene_target')); // Keep the original event.
 assert(events.some(event => event.type === 'keeper_scene_play'));
 const updates = events.filter(event => event.type === 'keeper_evaluate');
 assert.equal(updates.length, 2);
 assert.equal(updates[0].updateMode.mode, assignKeeper ? 0 : 3);
 assert.equal(updates[0].gate?.advanced ?? null, gateReached ? advance : null);
 assert.deepEqual(updates[0].sourceAfter, [advance && gateReached ? 0xc23e8000 : 0, 0, 0]);
 assert.equal(updates[1].frame, 3900); // still recording after the old 56-frame limit
 assert(!events.some(event => event.type.endsWith('_error')));
 return events.map(event => ({...event, captureId: 'paired-fixture'}));
}

assert.throws(() => fixture({supported: false}), /pinned 11\.1\.1/);
const fixtureDir = fs.mkdtempSync(path.join(os.tmpdir(), 'peerlink-keeper-test-'));
try {
 const game = path.join(fixtureDir, 'game.jsonl');
 const bot = path.join(fixtureDir, 'bot.jsonl');
 function compare(stockRecords, botRecords) {
  for (const [file, records] of [[game, stockRecords], [bot, botRecords]]) {
   fs.writeFileSync(file, records.map(record => JSON.stringify(record)).join('\n') + '\n');
  }
  return execFileSync('python3', [path.join(__dirname, 'compare-keeper-handoff.py'), game, bot], {encoding: 'utf8'});
 }
 const held = fixture({advance: false});
 fixture({advance: false, hostWrapper: true});
 const advances = fixture({advance: true});
 const gate = compare(advances, held);
 assert.match(gate, /Assigned movement[^\n]*\n  Paired readings agree/);
 assert.match(gate, /Motion controller decision[^\n]*\n  Gate decision reached in 1 game rows and 1 bot rows\n  First difference at frame 3842/);
 const assignment = compare(fixture({advance: true, assignKeeper: false}), held);
 assert.match(assignment, /Scene preparation[^\n]*\n  First difference at frame 3837/);
 const missing = compare(fixture({advance: false, gateReached: false}), held);
 assert.match(missing, /Gate decision reached in 0 game rows and 1 bot rows/);
 assert.match(compare(held, held), /Position copied into the match[^\n]*\n  Paired readings agree/);
} finally {
 fs.rmSync(fixtureDir, {recursive: true, force: true});
}
console.log('Keeper handoff fixture passed for role changes, controller holds, missing decisions and matching reads.');
