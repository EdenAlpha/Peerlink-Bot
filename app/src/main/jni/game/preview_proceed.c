typedef __UINTPTR_TYPE__ uptr;typedef unsigned long u64;extern int dprintf(int,const char *,...);extern int strcmp(const char *,const char *);
static const char *value(unsigned char *s){return (s[0]&1)?*(const char**)(s+16):(const char*)(s+1);}
static long original_proceed(uptr base, const char *expected){
 if(!base)return -1;
 uptr flow=*(uptr*)(base+0xa53b6e8),task=flow?*(uptr*)(flow+0x190):0;
 uptr storage=*(uptr*)(base+0x99ed5a0),map=storage?*(uptr*)storage:0;
 if(!flow||!task||!map||!(*(unsigned char*)(flow+0x170)&1))return -2;
 if(*(unsigned*)(flow+0x120)!=7)return -8;
 uptr a=*(uptr*)(task+0x70),e=*(uptr*)(task+0x78);if(!a||e<a||(e-a)%0xe0||e-a>0x1c00)return -3;
 uptr last=e>=a+0xe0?e-0xe0:0;if(!last||strcmp(value((unsigned char*)last),expected))return -4;
 unsigned char in[24]={14,'p','r','o','c','e','e','d',0},out[24]={0};
 ((void(*)(uptr,void*,void*))(base+0x6aad540))(map,out,in);
 const char *mapped=value(out);int ok=mapped&&!strcmp(mapped,"proceed");
 if(out[0]&1)((void(*)(void*))(base+0x2f376d4))(*(void**)(out+16));
 if(!ok)return -5;
 uptr start=*(uptr*)(flow+0x178),end=*(uptr*)(flow+0x180);
 if((!start&&end)||end<start||(end-start)%0x88||(end-start)>0x880)return -6;
 unsigned before=(unsigned)((end-start)/0x88);
 for(uptr p=start;p<end;p+=0x88)if(((int(*)(uptr))(base+0x6ab0120))(p)==0)return -7;
 ((void(*)(uptr,void*))(base+0x8102620))(0,in);
 start=*(uptr*)(flow+0x178);end=*(uptr*)(flow+0x180);
 unsigned after=end>=start?(unsigned)((end-start)/0x88):999;
 dprintf(2,"ORIGINAL_FLOW_PROCEED scene=%s pending_before=%u pending_after=%u\n",expected,before,after);
 return after>before?1:0;
}
__attribute__((visibility("default")))
long peerlink_original_preview_proceed_v271(uptr base){
 return original_proceed(base,"Match/Settings/MatchPreview");
}
__attribute__((visibility("default")))
long peerlink_original_preview_finish(uptr base){
 if(!base)return -1;
 uptr flow=*(uptr*)(base+0xa53b6e8),task=flow?*(uptr*)(flow+0x190):0;
 if(!flow||!task||task!=*(uptr*)(base+0xa53b780))return 0;
 unsigned state=*(unsigned*)(flow+0x120);
 if(state!=0&&state!=7&&state!=8)return 0;
 uptr first=*(uptr*)(task+0x70),end=*(uptr*)(task+0x78);
 if(!first||end<first+0xe0||(end-first)%0xe0||end-first>0x1c00)return 0;
 uptr leaf=end-0xe0;
 if(strcmp(value((unsigned char*)leaf),"Match/Settings/MatchPreview"))return 0;
 if(state==0){
  if(!*(unsigned char*)(leaf+0x50))return 0;
  uptr root=((uptr(*)(void))(base+0x7a87db8))(),info=root?*(uptr*)(root+0x20):0;
  uptr context=info?((uptr(*)(uptr))(base+0x7a58998))(info):0;
  if(!context||*(unsigned*)(context+0x2c)!=0)return 0;
  // This is the next original HOME flow observed after the preview in the
  // working match. Its own tasks finalize settings and synchronize readiness.
  // Request it through the same routing API used to enter the pre-match flow.
  ((void(*)(uptr,const char*))(base+0x6aa70a8))(flow+0x118,"Online/Match/MatchProcessMatchSettingsEnd");
  return 2;
 }
 if(!(*(unsigned char*)(flow+0x170)&1))return 0;
 // The same original scene/transition callbacks used for the headless
 // introduction. The caller has verified real two-user synchronization,
 // the Null driver, the exact preview wait and a queued Continue input.
 if(!*(unsigned char*)(leaf+0x50))((void(*)(uptr))(base+0x6aa9184))(leaf);
 ((void(*)(uptr,uptr))(base+0x6aa6ec0))(flow+0x118,leaf);
 return (*(uptr*)(flow+0x170)&4)?1:0;
}
__attribute__((visibility("default")))
long peerlink_original_demo_proceed(uptr base){
 // The game's own MatchEnterDemoOnline flow maps "proceed" to MatchIdle.
 return original_proceed(base,"Match/Enter/MatchEnterDemoOnline");
}
__attribute__((visibility("default")))
long peerlink_original_demo_finish(uptr base){
 if(!base)return -1;
 uptr flow=*(uptr*)(base+0xa53b6e8),task=flow?*(uptr*)(flow+0x190):0;
 uptr match=*(uptr*)(base+0xa533ec0);
 if(!flow||!task||task!=*(uptr*)(base+0xa53b780)||!match)return 0;
 unsigned phase=*(unsigned char*)(match+0x138);
 // The HUD can complete between host polls, advancing phase 13 to 12.
 // Either phase is still the original pre-match setup, not live play.
 if((phase!=13&&phase!=12)||*(unsigned*)(flow+0x120)!=7||
    !(*(unsigned char*)(flow+0x170)&1))return 0;
 uptr first=*(uptr*)(task+0x70),end=*(uptr*)(task+0x78);
 if(!first||end<first+0xe0||(end-first)%0xe0||end-first>0x1c00)return 0;
 uptr leaf=end-0xe0;
 if(strcmp(value((unsigned char*)leaf),"Match/Enter/MatchEnterDemoOnline"))return 0;
 // These are the game's own scene and transition completion callbacks.
 // Only the exact headless introduction is skipped: the original engine
 // continues to decide network, player and match-simulation readiness.
 if(!*(unsigned char*)(leaf+0x50))((void(*)(uptr))(base+0x6aa9184))(leaf);
 ((void(*)(uptr,uptr))(base+0x6aa6ec0))(flow+0x118,leaf);
 return (*(uptr*)(flow+0x170)&4)?1:0;
}
