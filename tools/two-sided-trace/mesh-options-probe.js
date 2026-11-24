'use strict';
// Read-only research probe for this pinned 11.1.1 libUE4.so only.
// UClass globals come from each class registration function. The option offset
// 0x5ec is from the original SkinnedMeshComponent property descriptor.
const lib=Process.findModuleByName('libUE4.so');
if(!lib)throw new Error('libUE4.so is not loaded');
const base=lib.base,at=x=>base.add(x);
const classes=[['Skeletal',0xa44c490],['Pes',0x9da4f78],['Mob',0x9d972a8],['HQMob',0x9d8cd68],['PesChild',0x9d9fb00]];
function ptrPattern(p){let h=p.toString().slice(2).padStart(Process.pointerSize*2,'0');return h.match(/../g).reverse().join(' ');}
function inspect(){
 const ranges=Process.enumerateRanges({protection:'rw-',coalesce:true}).filter(r=>r.size>0&&r.size<=268435456);
 const summary=[];
 for(const [label,offset] of classes){
  let klass;try{klass=at(offset).readPointer();}catch(_){continue;}
  if(klass.isNull()){summary.push({class:label,initialized:false});continue;}
  let valid=0, scanErrors=0;const histogram={};const seen=new Set();const pattern=ptrPattern(klass);
  for(const range of ranges){
   let found;try{found=Memory.scanSync(range.base,range.size,pattern);}catch(_){scanErrors++;continue;}
   for(const hit of found){
    try{
     const obj=hit.address.sub(0x10);if(seen.has(obj.toString()))continue;
     if(!obj.add(0x10).readPointer().equals(klass))continue;
     const index=obj.add(0xc).readS32();if(index<0||index>10000000)continue;
     const vtable=obj.readPointer();const mod=Process.findModuleByAddress(vtable);
     if(!mod||mod.name!=='libUE4.so')continue;
     const option=obj.add(0x5ec).readU8();if(option>7)continue;
     seen.add(obj.toString());valid++;histogram[option]=(histogram[option]||0)+1;
    }catch(_){}
   }
  }
  summary.push({class:label,initialized:true,objects:valid,options:histogram,scanErrors});
 }
 send({type:'mesh_options',classes:summary,rangeCount:ranges.length});
}
setImmediate(inspect);
