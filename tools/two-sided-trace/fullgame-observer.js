'use strict';

// 11.1.1 only: the lab verifies the original libUE4.so SHA-256 before use.
// Record only numeric match state, never account identifiers or packet data.
const lib = Process.findModuleByName('libUE4.so');
if (lib === null) throw new Error('libUE4.so is not loaded');
const base = lib.base;
const addr = off => base.add(off);
const make = (off, ret, args) => new NativeFunction(addr(off), ret, args);
const match = make(0x6b00048, 'uint32', ['pointer', 'pointer', 'pointer', 'pointer']);
const ball = make(0x6b00474, 'void', ['pointer', 'pointer', 'pointer']);
const object = make(0x6b0050c, 'void', ['pointer', 'pointer', 'pointer', 'uint32']);
const player = [0x6b00650, 0x6b00794, 0x6b00908].map(
  off => make(off, 'void', ['pointer', 'pointer', 'pointer', 'uint32', 'uint32']));
const gameBall = make(0x72d9980, 'pointer', ['pointer', 'uint32']);
const getRoot = make(0x7a87db8, 'pointer', []);
const getContext = make(0x7a58998, 'pointer', ['pointer']);
const getSimulation = make(0x698f7e0, 'pointer', []);
const getCursors = make(0x698f7f0, 'pointer', ['pointer']);
const getLocalPad = make(0x8223884, 'uint32', []);
const getSlot = make(0x698f7f8, 'uint32', ['pointer', 'uint32']);
const getCursor = make(0x698fac0, 'pointer', ['pointer', 'uint32']);
const cursorPlayer = make(0x6a4eb78, 'uint32', ['pointer']);
const directSelected = make(0x6b75188, 'uint32', []);
const getPossession = make(0x72c3ddc, 'pointer', ['pointer']);
const getPossessionFallback = make(0x72c745c, 'pointer', ['pointer']);
const possessionIndex = make(0x6b60a00, 'pointer', ['pointer']);
const getKeeper = make(0x733d0f4, 'uint32', ['pointer', 'uint32']);

function controlState() {
  const match = addr(0xa533ec0).readPointer();
  if (match.isNull() || match.add(0x138).readU8() !== 17) return null;
  const core = match.add(0x2f8).readPointer();
  if (core.isNull() || !core.readPointer().equals(addr(0x9899d50)) || core.add(8).readU32() !== 54) return null;
  const balls = core.add(0x3c98).readPointer();
  if (balls.isNull() || !balls.readPointer().equals(addr(0x989a160))) return null;
  const ball = balls.add(0x30).readPointer(), ballIndex = balls.add(0xc).readU32();
  if (ball.isNull() || ballIndex >= 8 || !balls.add(0x38 + ballIndex * 8).readPointer().equals(ball)) return null;
  const position = [0, 1, 2].map(i => ball.add(0x30 + i * 4).readFloat());
  const velocity = [0, 1, 2].map(i => ball.add(0x3c + i * 4).readFloat());
  if (position.some(v => !Number.isFinite(v) || Math.abs(v) > 80) ||
      velocity.some(v => !Number.isFinite(v) || Math.abs(v) > 140)) return null;
  const root = getRoot(), info = root.isNull() ? ptr(0) : root.add(0x20).readPointer();
  const context = info.isNull() ? ptr(0) : getContext(info);
  if (context.isNull()) return null;
  const side = context.add(0x2c).readU32();
  if (side > 1) return null;
  const sim = getSimulation();
  if (sim.isNull()) return null;
  const cursors = getCursors(sim);
  let selected = -1;
  if (!cursors.isNull()) {
    const slot = getSlot(cursors, getLocalPad());
    if (slot < 24) {
      const cursor = getCursor(cursors, slot);
      if (!cursor.isNull()) {
        const player = cursorPlayer(cursor);
        if (player < 22 && Math.floor(player / 11) === side) selected = player;
      }
    }
    if (selected < 0) {
      const player = directSelected();
      if (player < 22 && Math.floor(player / 11) === side) selected = player;
    }
  }
  const ballContext = balls.add(0x58);
  let possession = getPossession(ballContext);
  if (possession.isNull()) possession = getPossessionFallback(sim);
  let owner = -2;
  if (!possession.isNull()) {
    const index = possessionIndex(possession);
    if (!index.isNull()) {
      const value = index.readU32();
      if (value < 22) owner = value;
      else if (value === 255) owner = -1;
    }
  }
  const ownKeeper = getKeeper(ballContext, side), opposingKeeper = getKeeper(ballContext, 1 - side);
  return {side, selected, owner,
    ownKeeper: ownKeeper < 22 && Math.floor(ownKeeper / 11) === side ? ownKeeper : -1,
    opposingKeeper: opposingKeeper < 22 && Math.floor(opposingKeeper / 11) !== side ? opposingKeeper : -1,
    position, velocity};
}

function frameState(frame, original) {
  const handle = addr(0xa5909a0).readPointer();
  if (handle.isNull()) return {verified: false, reason: 'state-handle-null'};
  const state = handle.readPointer();
  if (state.isNull()) return {verified: false, reason: 'state-null'};
  const sum = Memory.alloc(4), count = Memory.alloc(4), observedFrame = Memory.alloc(4);
  sum.writeU32(0); count.writeU32(0); observedFrame.writeU32(0);
  if (!(match(state, sum, count, observedFrame) & 1)) return {verified: false, reason: 'state-unavailable'};
  const parts = [], terms = [];
  let prevSum = 0, prevCount = 0;
  function record() {
    const nextSum = sum.readU32(), nextCount = count.readU32();
    parts.push((nextSum - prevSum) >>> 0);
    terms.push((nextCount - prevCount) >>> 0);
    prevSum = nextSum; prevCount = nextCount;
  }
  record(); ball(state, sum, count); record();
  for (let i = 0; i < 3; i++) { object(state, sum, count, i); record(); }
  for (let side = 0; side < 2; side++) for (let slot = 0; slot < 11; slot++) {
    for (const fn of player) { fn(state, sum, count, side, slot); record(); }
  }
  const raw = sum.readU32();
  const checksum = (raw + (raw >>> 16)) & 0xffff;
  const result = {verified: checksum === original && observedFrame.readU32() === frame,
    observedFrame: observedFrame.readU32(), observedValue: checksum};
  if (!result.verified) return result;
  result.terms = count.readU32(); result.parts = parts; result.partTerms = terms;
  try {
    const coordinates = [];
    for (let i = 0; i < 22; i++) {
      const p = addr(0xa5909a0 + 0x170 + i * 8).readPointer();
      coordinates.push(p.isNull() ? null : [p.add(0x5dc).readU32(), p.add(0x5e0).readU32(), p.add(0x5e4).readU32()]);
    }
    result.playerPositionBits = coordinates;
  } catch (_) { result.playerPositionsUnavailable = true; }
  try {
    const m = addr(0xa58de28).readPointer();
    if (!m.isNull()) {
      const selected = m.add(0x54).readU32();
      const history = selected < 3 ? m.add(0x70 + selected * 0x50).readPointer() : ptr(0);
      if (!history.isNull()) {
        const b = gameBall(history, 0);
        if (!b.isNull()) result.ballBits = Array.from({length: 7}, (_, i) => b.add(i * 4).readU32());
      }
    }
  } catch (_) { result.ballBitsUnavailable = true; }
  try { result.control = controlState(); }
  catch (_) { result.controlUnavailable = true; }
  return result;
}

function currentCommandFrame() {
  const service = addr(0x9a6a020).readPointer();
  if (service.isNull() || !service.readPointer().equals(addr(0x98ae9e0))) return 0;
  const inner = service.add(8).readPointer();
  if (inner.isNull()) return 0;
  const controller = inner.add(0xa0).readPointer();
  if (controller.isNull() || !controller.readPointer().equals(addr(0x98aedd0))) return 0;
  return controller.add(0x30).readU32();
}

function sceneState(scene, transition) {
  if (scene.isNull() || transition.isNull() || scene.add(4).readU32() !== 8) return null;
  const commandFrame = currentCommandFrame();
  if (!commandFrame) return null;
  const sample = {commandFrame, sceneId: scene.add(8).readU32(), transitionFlag: transition.add(0x22e).readU8()};
  const actor = scene.add(0x280).readPointer();
  const vtable = actor.isNull() ? ptr(0) : actor.readPointer();
  sample.actorVerified = !vtable.isNull() && vtable.add(0x28).readPointer().equals(addr(0x69d25bc));
  if (sample.actorVerified) {
    sample.actorMode = actor.add(0x250).readU32();
    sample.startRequested = actor.add(0x5f).readU8() !== 0;
    sample.bindingPresent = !actor.add(0x228).readPointer().isNull();
    sample.interrupted = actor.add(0x60).readU8() !== 0;
    const manager = addr(0xa531eb0).readPointer();
    sample.managerPresent = !manager.isNull();
    if (sample.managerPresent) sample.managerFinishesEarly = manager.add(0x301e).readU8() !== 0 ||
      (manager.add(0x301f).readU8() !== 0 && manager.add(0x3010).readU32() === 0);
  }
  return sample;
}

Interceptor.attach(addr(0x7053d8c), {
  onEnter(args) {
    this.wanted = this.returnAddress.equals(addr(0x7053bbc));
    if (this.wanted) this.framePointer = args[2];
  },
  onLeave(ret) {
    if (!this.wanted || this.framePointer.isNull()) return;
    try {
      const frame = this.framePointer.readU32(), value = ret.toUInt32() & 0xffff;
      if (!frame) return;
      const started = Date.now();
      const snapshot = frameState(frame, value);
      send({type: 'match_check', frame, value, snapshot, captureMs: Date.now() - started});
    } catch (e) { send({type: 'observer_error', site: 'match_check', error: String(e)}); }
  }
});
Interceptor.attach(addr(0x6a46c84), {
  onEnter() {
    this.wanted = this.returnAddress.equals(addr(0x6fe8614));
    if (!this.wanted) return;
    try {
      this.scene = this.context.x20; this.transition = this.context.x19;
      this.before = sceneState(this.scene, this.transition);
    } catch (_) { this.before = null; }
  },
  onLeave() {
    if (!this.wanted || this.before === null || this.before === undefined) return;
    try {
      const after = sceneState(this.scene, this.transition);
      if (after && after.commandFrame === this.before.commandFrame && after.sceneId === this.before.sceneId)
        send({type: 'scene_start', before: this.before, after});
    } catch (e) { send({type: 'observer_error', site: 'scene_start', error: String(e)}); }
  }
});
send({type: 'observer_ready', engine: '11.1.1', hooks: ['match_check', 'scene_start']});
