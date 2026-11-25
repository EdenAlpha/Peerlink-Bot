'use strict';
// Read-only comparison of the game's scene target setup on 11.1.1.
const lib=Process.findModuleByName('libUE4.so');
if(lib===null) throw new Error('libUE4.so is not loaded');
const base=lib.base, at=x=>base.add(x);
const entryWords = new Map([
 [0x6fe89b8, 0xd101c3ff], [0x6fe7494, 0xa9bd7bfd],
 [0x725b0d0, 0xa9be7bfd], [0x725b114, 0x360000a0], [0x725b134, 0xfc1b0fe8],
 [0x6d3f944, 0xa9bd7bfd],
]);
if (Process.arch !== 'arm64' || [...entryWords].some(([offset, word]) => at(offset).readU32() !== word)) {
 throw new Error('Scene observer needs the pinned 11.1.1 ARM64 engine');
}
let activeScene = null;
const evaluating = new Map();
// Keep recording the last verified scene's keeper decisions until a later
// verified scene replaces it. Restarts as well as celebrations can desync.

function currentFrame(){
 try {const s=at(0x9a6a020).readPointer();if(s.isNull()||!s.readPointer().equals(at(0x98ae9e0)))return 0;
 const i=s.add(8).readPointer();if(i.isNull())return 0;const c=i.add(0xa0).readPointer();
 if(c.isNull()||!c.readPointer().equals(at(0x98aedd0)))return 0;return c.add(0x30).readU32();}catch(_){return 0;}
}
function engineOffset(p){try{const m=Process.findModuleByAddress(p);return m && m.name==='libUE4.so' ? p.sub(base).toUInt32():null;}catch(_){return null;}}

// Use the existing player-update hook: adding a second independent hook here
// changes the entry instruction and defeats the recorder's version check.
let lastTimelineFrame = null, timelineSequence = 0;
function timelineBallBits() {
 const match = at(0xa533ec0).readPointer();
 if (match.isNull() || match.add(0x138).readU8() !== 17) return null;
 const core = match.add(0x2f8).readPointer();
 if (core.isNull() || !core.readPointer().equals(at(0x9899d50)) || core.add(8).readU32() !== 54) return null;
 const balls = core.add(0x3c98).readPointer();
 if (balls.isNull() || !balls.readPointer().equals(at(0x989a160))) return null;
 const ball = balls.add(0x30).readPointer(), index = balls.add(0xc).readU32();
 if (ball.isNull() || index >= 8 || !balls.add(0x38 + index * 8).readPointer().equals(ball)) return null;
 return Array.from({length: 6}, (_, i) => ball.add(0x30 + i * 4).readU32());
}
function recordTimelineFrame(object) {
 try {
  const anchor = at(0xa5909a0 + 0x170).readPointer();
  if (anchor.isNull() || !object.equals(anchor)) return;
  const frame = currentFrame();
  if (!frame || frame === lastTimelineFrame) return;
  const started = Date.now(), positions = [];
  for (let i = 0; i < 22; i++) {
   const player = at(0xa5909a0 + 0x170 + i * 8).readPointer();
   positions.push(player.isNull() ? null : [player.add(0x5dc).readU32(), player.add(0x5e0).readU32(),
    player.add(0x5e4).readU32(), player.add(0x798).readU8()]);
  }
  const ballBits = timelineBallBits(), previousFrame = lastTimelineFrame;
  lastTimelineFrame = frame;
  send({type: 'frame_state', sequence: ++timelineSequence, frame, previousFrame,
   skippedFrames: previousFrame !== null && frame > previousFrame ? frame - previousFrame - 1 : null,
   rewound: previousFrame !== null && frame < previousFrame,
   positions, ballBits, captureMs: Date.now() - started});
 } catch (error) { send({type: 'frame_observer_error', error: String(error)}); }
}

function keeperSlots() {
 const simulation = at(0xa58de28).readPointer();
 if (simulation.isNull()) return [];
 const slots = [];
 for (const side of [0, 1]) {
  const roster = simulation.add(0x3030 + side * 0x50).readPointer();
  if (roster.isNull()) continue;
  for (let slot = 0; slot < 11; slot++) {
   if (roster.add(0x42b8 + slot * 4).readU32() !== 0) continue;
   slots.push({side, player: side * 11 + slot});
   break;
  }
 }
 return slots;
}

function readAssignments(transition, slots) {
 return slots.map(slot => ({...slot, ...readMode(transition, slot.player)}));
}

function readMode(transition, player) {
 const mode = transition.add(0xc + player * 4).readU32();
 return {mode, override: transition.add(0x20c + player).readU8() !== 0,
  target: mode === 2 ? positionBits(transition.add(0x88 + player * 12)) : null};
}

function playerUpdateMode(player) {
 const simulation = at(0xa58de28).readPointer();
 if (simulation.isNull()) return null;
 const transition = simulation.add(0x390).readPointer();
 if (transition.isNull()) return null;
 return {active: transition.add(0x22e).readU8() !== 0,
  locked: transition.add(0x22f).readU8() !== 0, ...readMode(transition, player)};
}

function castCount(actor, offset) {
 const start = actor.add(offset).readPointer();
 const end = actor.add(offset + 8).readPointer();
 if (end.equals(start)) return 0;
 if (start.isNull() || end.compare(start) < 0 || end.compare(start.add(0xffffffff)) > 0) return null;
 const size = end.sub(start).toUInt32();
 return size % 12 === 0 ? size / 12 : null;
}

Interceptor.attach(at(0x6fe89b8), {
 onEnter(args) {
  if (!this.returnAddress.equals(at(0x6fe85e8))) return;
  try {
   const scene = args[0];
   const frame = currentFrame();
   // Scene preparation is called in state 5; state 8 is the later play call.
   if (!frame || scene.isNull() || args[2].isNull() || scene.add(4).readU32() !== 5) return;
   const sceneId = scene.add(8).readU32();
   if (sceneId === 0) return;
   activeScene = null;
   const actor = scene.add(0x280).readPointer();
   const table = actor.isNull() ? ptr(0) : actor.readPointer();
   if (table.isNull() || !table.add(0x28).readPointer().equals(at(0x69d25bc))) return;
   const rolesLoaded = scene.add(3).readU8() !== 0;
   const slots = keeperSlots();
   this.capture = {scene, sceneId, frame, slots, playFrame: null};
   this.transition = args[2];
   this.record = {
    type: 'keeper_scene_prepare', sceneId, frame, rolesLoaded,
    loadedRoles: castCount(actor, 0x80), availableRoles: castCount(actor, 0xc0),
    before: readAssignments(args[2], slots),
   };
   activeScene = this.capture;
  } catch (_) { send({type: 'keeper_observer_error', site: 'scene_prepare'}); }
 },
 onLeave() {
  if (!this.record) return;
  try {
   this.record.after = readAssignments(this.transition, this.capture.slots);
   send(this.record);
  } catch (_) { send({type: 'keeper_observer_error', site: 'scene_prepare_result'}); }
 },
});

Interceptor.attach(at(0x6fe7494),{
 onEnter(args){
  const frame=currentFrame();if(!frame)return;
  try{
   const context=args[0], transition=args[1]; if(context.isNull()||transition.isNull())return;
   const index=context.add(8).readS32(); if(index<0||index>8)return;
   const participant=context.add(0x30+index*8).readPointer();
   const target=context.add(0x680).readPointer();
   let handler=null;
   if(!participant.isNull()) {const vt=participant.readPointer();if(!vt.isNull())handler=engineOffset(vt.add(0x38).readPointer());}
   const keeper=at(0xa5909a0+0x170).readPointer();
   this.record={type:'scene_target',frame,index,handler,targetPresent:!target.isNull(),targetIsKeeper:!keeper.isNull()&&target.equals(keeper),transitionFlag:transition.add(0x22e).readU8()};
   const scene = activeScene;
   const caller = Process.findModuleByAddress(this.returnAddress);
   // The stock game calls this directly. PeerLink's existing scene observer
   // calls the same original routine through its native host instead.
   const sceneCaller = this.returnAddress.equals(at(0x6fe8634)) ||
       (caller && caller.name === 'libpeerlinkbackend.so');
   if (scene && sceneCaller && scene.playFrame === null &&
       scene.scene.equals(context.add(0x3f0)) &&
       frame >= scene.frame && frame - scene.frame <= 12) {
    scene.playFrame = frame;
    this.play = {scene, transition};
   }
  } catch(e){send({type:'scene_target_error',error:String(e)});}
 },
 onLeave(){
  if(this.record)send(this.record);
  if (!this.play) return;
  const {scene, transition} = this.play;
  try {
   send({type: 'keeper_scene_play', sceneId: scene.sceneId, frame: scene.playFrame,
    preparedAt: scene.frame, assignments: readAssignments(transition, scene.slots)});
  } catch (_) { send({type: 'keeper_observer_error', site: 'scene_play'}); }
 }
});

function positionBits(position) {
 return [0, 4, 8].map(offset => position.add(offset).readU32());
}

function trackPosition(controller) {
 if (controller.isNull()) return null;
 const track = controller.add(0xa88).readPointer();
 if (track.isNull()) return null;
 const pose = track.add(0x198).readPointer();
 return pose.isNull() ? null : positionBits(pose.add(0x40));
}

// These are raw words used by the game's checksum. Their meanings are not
// established, so retain their offsets rather than guessing what they mean.
function controllerWords(controller) {
 return controller.isNull() ? null : {
  wordAE8: controller.add(0xae8).readU16(),
  wordAEA: controller.add(0xaea).readU16(),
 };
}

let keeperCacheFrame = null, keeperCache = [];
function currentKeepers(frame) {
 if (frame !== keeperCacheFrame) {
  keeperCacheFrame = frame;
  keeperCache = keeperSlots();
 }
 return keeperCache;
}

function observedKeeper(object, frame) {
 if (!frame || object.isNull()) return null;
 let scene = activeScene;
 // A prepared scene that never plays must not be treated as active, but it
 // must not prevent the keeper trace from recording subsequent frames.
 if (scene && scene.playFrame === null && frame > scene.frame + 12) {
  send({type: 'keeper_scene_play_missing', sceneId: scene.sceneId, preparedAt: scene.frame, frame});
  activeScene = null;
  scene = null;
 }
 const player = object.add(0x18).readU32();
 if (player >= 22 || !object.equals(at(0xa5909a0 + 0x170 + player * 8).readPointer())) return null;
 // Cache at most once per command frame; the hook runs for all 22 players.
 const role = currentKeepers(frame).find(slot => slot.player === player);
 if (!role) return null;
 return {sceneId: scene && frame >= scene.frame ? scene.sceneId : null, frame, side: role.side, player};
}

// One identified native setter compares the current controller word at
// +0xae8 with its requested value and writes it only when they differ.
// Observe its caller for either keeper, without replacing its behavior. Other
// code may write the same word through a different path; absence of this event
// is therefore not proof that no write occurred.
Interceptor.attach(at(0x6d3f944), {
 onEnter(args) {
  try {
   const frame = currentFrame(), controller = args[0];
   if (!frame || controller.isNull()) return;
   for (const role of currentKeepers(frame)) {
    const player = at(0xa5909a0 + 0x170 + role.player * 8).readPointer();
    if (player.isNull() || !player.add(0x7a8).readPointer().equals(controller)) continue;
    const before = controller.add(0xae8).readU16();
    const requested = args[1].toUInt32() & 0xffff;
    if (before === requested) return;
    const offset = engineOffset(this.returnAddress);
    const scene = activeScene;
    this.record = {type: 'keeper_state_request', frame, side: role.side, player: role.player,
     sceneId: scene && frame >= scene.frame ? scene.sceneId : null,
     before, requested, callSite: offset === null ? null : offset - 4};
    this.controller = controller;
    return;
   }
  } catch (_) { send({type: 'keeper_observer_error', site: 'state_request'}); }
 },
 onLeave() {
  if (!this.record) return;
  try {
   this.record.after = this.controller.add(0xae8).readU16();
   send(this.record);
  } catch (_) { send({type: 'keeper_observer_error', site: 'state_request_result'}); }
 },
});

function observeKeeper(offset, type, observesGate) {
 Interceptor.attach(at(offset), {
  onEnter(args) {
   if (offset === 0x725b0d0) recordTimelineFrame(args[0]);
   try {
    const player = args[0];
    const record = observedKeeper(player, currentFrame());
    if (!record) return;
    const controller = player.add(0x7a8).readPointer();
    this.record = {type, ...record, active: player.add(0x798).readU8() !== 0,
     positionBefore: positionBits(player.add(0x5dc)), sourceBefore: trackPosition(controller),
     controllerWordsBefore: controllerWords(controller)};
    this.player = player;
    if (observesGate) {
     this.record.updateMode = playerUpdateMode(record.player);
     this.record.gate = null;
     evaluating.set(this.threadId, this);
    }
   } catch (_) { send({type: 'keeper_observer_error', site: type}); }
  },
  onLeave() {
   if (!this.record) return;
   if (evaluating.get(this.threadId) === this) evaluating.delete(this.threadId);
   try {
    const controller = this.player.add(0x7a8).readPointer();
    this.record.sourceAfter = trackPosition(controller);
    this.record.controllerWordsAfter = controllerWords(controller);
    this.record.positionAfter = positionBits(this.player.add(0x5dc));
    send(this.record);
   } catch (_) { send({type: 'keeper_observer_error', site: type + '_result'}); }
  },
 });
}

observeKeeper(0x725b0d0, 'keeper_evaluate', true);
observeKeeper(0x725b134, 'keeper_position', false);
// The original evaluation helper has returned. Read its actual result before
// the next instruction selects the movement routine or returns.
Interceptor.attach(at(0x725b114), function () {
 const update = evaluating.get(this.threadId);
 if (!update || !update.player.equals(this.context.x19)) return;
 try {
  const controller = update.player.add(0x7a8).readPointer();
  if (controller.isNull()) return;
  update.record.gate = {
   requested: controller.add(0xa82).readU8() !== 0,
   held: controller.add(0xa83).readU8() !== 0,
   advanced: (this.context.x0.toUInt32() & 1) !== 0,
  };
 } catch (_) { send({type: 'keeper_observer_error', site: 'keeper_evaluate_gate'}); }
});
send({type:'scene_target_observer_ready', keeperHandoff: 1, frameTimeline: 1});
