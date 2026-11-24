'use strict';
const assert=require('assert');
const vm=require('vm');
const fs=require('fs');
const base=0x100000000;
const memory=new Map();
class P {
  constructor(v){this.v=Number(v);}
  add(x){return new P(this.v+Number(x));}
  sub(x){return new P(this.v-x.v);}
  equals(x){return this.v===x.v;}
  isNull(){return this.v===0;}
  toUInt32(){return this.v>>>0;}
  readU32(){return memory.get(this.v)>>>0;}
  readFloat(){return memory.get(this.v);}
  readPointer(){return new P(memory.get(this.v)||0);}
}
const hooks=new Map(), sent=[];
const ctx={Process:{findModuleByName:()=>({base:new P(base)})},
  Interceptor:{attach:(p, callbacks)=>hooks.set(p.v-base,callbacks)},
  send:e=>sent.push(e),ptr:v=>new P(v)};
vm.runInNewContext(fs.readFileSync('keeper-observer.js','utf8'),ctx);
const service=0x220000,inner=0x230000,controller=0x240000,player=0x250000;
memory.set(base+0x9a6a020,service);memory.set(service,base+0x98ae9e0);
memory.set(service+8,inner);memory.set(inner+0xa0,controller);
memory.set(controller,base+0x98aedd0);memory.set(controller+0x30,1234);
memory.set(base+0xa5909a0+0x170,player);
[1,0,3].forEach((v,i)=>memory.set(player+0x5dc+i*4,v));
const total=0x310000, local=0x320000, vector=0x330000;
memory.set(total,100);memory.set(local,7);[4,5,6].forEach((v,i)=>memory.set(vector+i*4,v));
let call={threadId:17};
hooks.get(0x6b00794).onEnter.call(call,[new P(1),new P(total),new P(2),new P(0),new P(0)]);
let helper={threadId:17,returnAddress:new P(base+0x6b00800)};
hooks.get(0x6b00d20).onEnter.call(helper,[new P(vector),new P(local),new P(2)]);
memory.set(local,26);hooks.get(0x6b00d20).onLeave.call(helper);
memory.set(total,119);hooks.get(0x6b00794).onLeave.call(call);
const record=sent.find(x=>x.type==='keeper_component');
assert(record && record.frame===1234 && record.part===2 && record.total===19);
assert.deepStrictEqual(Array.from(record.position),[1,0,3]);
assert(record.terms.length===1 && record.terms[0].delta===19 && record.terms[0].call===0x6b007fc);
assert.deepStrictEqual(Array.from(record.terms[0].value),[4,5,6]);
call={threadId:18};
hooks.get(0x6b00794).onEnter.call(call,[new P(1),new P(total),new P(2),new P(1),new P(0)]);
hooks.get(0x6b00794).onLeave.call(call);
assert(sent.filter(x=>x.type==='keeper_component').length===1);
console.log('Keeper observer smoke test passed: player filter, position, per-call checksum delta.');
