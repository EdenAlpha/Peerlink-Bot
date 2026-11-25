'use strict';
// Read-only comparison of the game's scene target setup on 11.1.1.
const lib=Process.findModuleByName('libUE4.so');
if(lib===null) throw new Error('libUE4.so is not loaded');
const base=lib.base, at=x=>base.add(x);
const entryWords = new Map([
 [0x6fe89b8, 0xd101c3ff], [0x6fe7494, 0xa9bd7bfd],
 [0x725b0d0, 0xa9be7bfd], [0x725b114, 0x360000a0], [0x725b134, 0xfc1b0fe8],
]);
if (Process.arch !== 'arm64' || [...entryWords].some(([offset, word]) => at(offset).readU32() !== word)) {
 throw new Error('Scene observer needs the pinned 11.1.1 ARM64 engine');
}
let activeScene = null;
const evaluating = new Map();
// Keep recording the last played goal's keeper decisions until another goal
// replaces it. The role and gate record is not limited to a short window.

function currentFrame(){
 try {const s=at(0x9a6a020).readPointer();if(s.isNull()||!s.readPointer().equals(at(0x98ae9e0)))return 0;
 const i=s.add(8).readPointer();if(i.isNull())return 0;const c=i.add(0xa0).readPointer();
 if(c.isNull()||!c.readPointer().equals(at(0x98aedd0)))return 0;return c.add(0x30).readU32();}catch(_){return 0;}
}
function engineOffset(p){try{const m=Process.findModuleByAddress(p);return m && m.name==='libUE4.so' ? p.sub(base).toUInt32():null;}catch(_){return null;}}

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
   if (!frame || scene.isNull() || args[2].isNull() || scene.add(4).readU32() !== 8) return;
   const sceneId = scene.add(8).readU32();
   if ((sceneId >>> 16) !== 3) return;
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

function observedKeeper(object, frame) {
 const scene = activeScene;
 if (!scene || frame < scene.frame) return null;
 // A prepared scene that never plays must not be treated as an active goal.
 if (scene.playFrame === null && frame > scene.frame + 12) {
  send({type: 'keeper_scene_play_missing', sceneId: scene.sceneId, preparedAt: scene.frame, frame});
  activeScene = null;
  return null;
 }
 if (object.isNull()) return null;
 const player = object.add(0x18).readU32();
 const role = scene.slots.find(slot => slot.player === player);
 if (!role || !object.equals(at(0xa5909a0 + 0x170 + player * 8).readPointer())) return null;
 return {sceneId: scene.sceneId, frame, side: role.side, player};
}

function observeKeeper(offset, type, observesGate) {
 Interceptor.attach(at(offset), {
  onEnter(args) {
   if (!activeScene) return;
   try {
    const player = args[0];
    const record = observedKeeper(player, currentFrame());
    if (!record) return;
    const controller = player.add(0x7a8).readPointer();
    this.record = {type, ...record, active: player.add(0x798).readU8() !== 0,
     positionBefore: positionBits(player.add(0x5dc)), sourceBefore: trackPosition(controller)};
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
    this.record.sourceAfter = trackPosition(this.player.add(0x7a8).readPointer());
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
send({type:'scene_target_observer_ready', keeperHandoff: 1});
