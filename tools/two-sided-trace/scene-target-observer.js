'use strict';
// Read-only comparison of the game's scene target setup on 11.1.1.
const lib=Process.findModuleByName('libUE4.so');
if(lib===null) throw new Error('libUE4.so is not loaded');
const base=lib.base, at=x=>base.add(x);
function currentFrame(){
 try {const s=at(0x9a6a020).readPointer();if(s.isNull()||!s.readPointer().equals(at(0x98ae9e0)))return 0;
 const i=s.add(8).readPointer();if(i.isNull())return 0;const c=i.add(0xa0).readPointer();
 if(c.isNull()||!c.readPointer().equals(at(0x98aedd0)))return 0;return c.add(0x30).readU32();}catch(_){return 0;}
}
function engineOffset(p){try{const m=Process.findModuleByAddress(p);return m && m.name==='libUE4.so' ? p.sub(base).toUInt32():null;}catch(_){return null;}}
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
  } catch(e){send({type:'scene_target_error',error:String(e)});}
 },
 onLeave(){if(this.record)send(this.record);}
});
send({type:'scene_target_observer_ready'});
