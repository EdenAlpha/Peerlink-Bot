typedef __UINTPTR_TYPE__ uptr;typedef unsigned long u64;extern int dprintf(int,const char *,...);extern int strcmp(const char *,const char *);
static const char *value(unsigned char *s){return (s[0]&1)?*(const char**)(s+16):(const char*)(s+1);}
__attribute__((visibility("default")))
long peerlink_original_preview_proceed_v271(uptr base){
 if(!base)return -1;
 uptr flow=*(uptr*)(base+0xa53b6e8),task=flow?*(uptr*)(flow+0x190):0;
 uptr storage=*(uptr*)(base+0x99ed5a0),map=storage?*(uptr*)storage:0;
 if(!flow||!task||!map||!(*(unsigned char*)(flow+0x170)&1))return -2;
 uptr a=*(uptr*)(task+0x70),e=*(uptr*)(task+0x78);if(!a||e<a||(e-a)%0xe0||e-a>0x1c00)return -3;
 uptr last=e>=a+0xe0?e-0xe0:0;if(!last||strcmp(value((unsigned char*)last),"Match/Settings/MatchPreview"))return -4;
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
 dprintf(2,"ORIGINAL_PREVIEW_PROCEED event=proceed pending_before=%u pending_after=%u\n",before,after);
 return after>before?1:0;
}
