/* Supply actual owned participant IDs through the original model factory and
 * setter, within its existing original task manager's recursive mutex.
 * Preserve the full app's existing task roots. Engine threads own all updates.
 * No manual model ticks or game-readiness/completion writes are performed.
 */
typedef __UINTPTR_TYPE__ uptr;
typedef unsigned int u32;
extern int dprintf(int,const char *,...);
__attribute__((visibility("default")))
uptr peerlink_original_online_inputs_v224(uptr base,u32 host,u32 away) {
 if(!base || !host || !away || host==away)return 0;
 uptr manager=((uptr (*)(u32))(base+0x822933c))(1);
 if(!manager || !*(uptr *)(manager+0x118))return 0;
 uptr root_before=*(uptr *)(manager+0x118);
 ((void (*)(uptr))(base+0x82298c8))(manager);
 uptr root=((uptr (*)(void))(base+0x7a87db8))();
 uptr model=0;
 if(root && *(uptr *)(root+0x20)) {
  model=((uptr (*)(void))(base+0x7a1d8d4))();
  if(!model)model=((uptr (*)(void))(base+0x7a1d39c))();
  if(model) {
   u32 participants[2]={host,away};
   uptr vector[3]={(uptr)participants,(uptr)(participants+2),(uptr)(participants+2)};
   ((void (*)(uptr,const void *))(base+0x7a1fd48))(model,vector);
  }
 }
 unsigned unchanged=root_before==*(uptr *)(manager+0x118);
 ((void (*)(uptr))(base+0x82298d0))(manager);
 dprintf(2,"ORIGINAL_ONLINE_INPUTS_SUPPLIED original_model_present=%u existing_task_root_preserved=%u engine_owns_updates=1\n",model!=0,unchanged);
 return unchanged?model:0;
}
