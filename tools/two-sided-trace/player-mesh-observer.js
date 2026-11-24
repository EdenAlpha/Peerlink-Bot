'use strict';
// Read-only samples at original player readiness function for 11.1.1 only.
const lib=Process.findModuleByName('libUE4.so');if(!lib)throw new Error('libUE4.so is not loaded');
const base=lib.base, at=x=>base.add(x), actors=new Map();let nextId=1;
function findPointer(container, length, needle){
 try{
  if(container.isNull()||needle.isNull())return [];
  const range=Process.findRangeByAddress(container);
  if(!range||container.add(length).compare(range.base.add(range.size))>0)return [];
  const pattern=needle.toString().slice(2).padStart(Process.pointerSize*2,'0').match(/../g).reverse().join(' ');
  return Memory.scanSync(container,length,pattern).map(hit=>hit.address.sub(container).toUInt32());
 }catch(_){return [];}
}
function keeperReferences(actor){
 try{
  const keeper=at(0xa5909a0+0x170).readPointer();if(keeper.isNull())return null;
  const control=keeper.add(0x7a8).readPointer();
  return {keeperToActor:findPointer(keeper,0x800,actor),actorToKeeper:findPointer(actor,0x1a80,keeper),
   controlToActor:findPointer(control,0x800,actor),actorToControl:findPointer(actor,0x1a80,control)};
 }catch(_){return null;}
}
function snapshot(actor){
 const result={};
 result.meshReady=actor.add(0x1a58).readU8();
 result.meshRequired=actor.add(0x1a5e).readU8();
 const assetHolder=actor.add(0x328).readPointer();
 const componentHolder=actor.add(0x1a70).readPointer();
 const asset=assetHolder.isNull()?ptr(0):assetHolder.add(8).readPointer();
 const component=componentHolder.isNull()?ptr(0):componentHolder.add(8).readPointer();
 result.assetPointerPresent=!asset.isNull();result.componentPresent=!component.isNull();
 if(!component.isNull()){
  try{let v=component.add(0x5ec).readU8();result.animTickOption=v<=3?v:null;}catch(_){result.animTickOption=null;}
 }
 return result;
}
Interceptor.attach(at(0x39d0688),{
 onEnter(args){this.actor=args[0];},
 onLeave(ret){
  if(!this.actor||this.actor.isNull())return;
  try{
   const key=this.actor.toString();let rec=actors.get(key);
   if(!rec){rec={id:nextId++,last:'',refs:null,probedReady:false};actors.set(key,rec);}
   const readiness=ret.toUInt32()&1;
   if(rec.refs===null || (readiness===1 && !rec.probedReady)) {
    rec.refs=keeperReferences(this.actor);
    if(readiness===1 && rec.refs!==null)rec.probedReady=true;
   }
   const state=snapshot(this.actor);
   if(rec.refs)state.keeperReferences=rec.refs;
   const sig=JSON.stringify([state,readiness]);
   if(sig!==rec.last){rec.last=sig;send({type:'player_mesh',actorId:rec.id,readiness,state});}
  }catch(e){send({type:'player_mesh_error',error:String(e)});}
 }
});
send({type:'player_mesh_observer_ready'});
