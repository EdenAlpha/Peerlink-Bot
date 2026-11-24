'use strict';
// Lab-only: avoid the unsafe host-poll snapshot; the game's normal checksum
// and per-frame diagnostics remain active. This does not patch the APK.
const lib=Process.findModuleByName('libpeerlinkbackend.so');
if(!lib)throw new Error('PeerLink native host not loaded');
const target=lib.getExportByName('_Z35peerlink_match_state_sample_initialv');
if(target.sub(lib.base).toUInt32()!==0x9c110)throw new Error('Unexpected host binary; refusing lab override');
let calls=0;
const replacement=new NativeCallback(function(){
 if(++calls===1)send({type:'lab_initial_snapshot_skipped'});
},'void',[]);
Interceptor.replace(target,replacement);
send({type:'lab_guard_ready',scope:'initial_diagnostic_only'});
