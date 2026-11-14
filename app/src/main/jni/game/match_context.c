/* Actual live-room inputs to original login and GameInfoModel APIs.
 * RequestStartGame itself is only inspected by this adapter. */
typedef __UINTPTR_TYPE__ uptr;
typedef __SIZE_TYPE__ usize;
typedef unsigned int u32;
typedef unsigned long long u64;
extern void *calloc(usize,usize);
extern void free(void*);
extern void *fopen(const char*,const char*);
extern usize fread(void*,usize,usize,void*);
extern int fclose(void*);
extern int dprintf(int,const char*,...);
struct Input {char magic[4];u32 uid;u64 game_id;u32 team_id,sid_size,category_size,keyword_size;};
struct String {uptr words[3];};
_Static_assert(sizeof(struct Input)==32,"live input header");

static int load(const char *path,struct Input *in,unsigned char data[584]){
 void *f=fopen(path,"rb");if(!f)return 0;
 int ok=fread(in,1,sizeof(*in),f)==sizeof(*in);
 if(!ok||in->magic[0]!='R'||in->magic[1]!='G'||in->magic[2]!='I'||in->magic[3]!='1'||
    !in->uid||!in->game_id||!in->team_id||!in->sid_size||in->sid_size>512||
    !in->category_size||in->category_size>64||in->keyword_size!=8){fclose(f);return 0;}
 usize n=in->sid_size+in->category_size+in->keyword_size;
 unsigned char extra;ok=fread(data,1,n,f)==n&&fread(&extra,1,1,f)==0;fclose(f);return ok;
}
static int equal(const unsigned char *a,const unsigned char *b,usize n){
 for(usize i=0;i<n;i++){if(a[i]!=b[i])return 0;}
 return 1;
}
static int string_equal(const void *object,const unsigned char *data,usize count){
 const unsigned char *s=object;const uptr *w=object;
 usize n=(s[0]&1)?w[1]:(s[0]>>1);
 const unsigned char *p=(s[0]&1)?(const unsigned char*)w[2]:s+1;
 return n==count&&p&&equal(p,data,n);
}
static void release_string(uptr base,struct String *s){
 if(s->words[0]&1)((void(*)(uptr))(base+0x2f376d4))(s->words[2]);
}

__attribute__((visibility("default")))
long peerlink_live_match_context_input(uptr base,const char *path){
 struct Input in;unsigned char data[584];if(!base||!path||!load(path,&in,data))return -1;
 uptr client=((uptr(*)(void))(base+0x7e02524))();
 uptr services=((uptr(*)(void))(base+0x7e0afbc))();
 uptr login=services?*(uptr*)(services+8):0;
 if(!client||*(u32*)(client+0x948)!=in.uid||!login||((u32(*)(uptr))(base+0x7e15704))(login)!=in.uid)return -2;
 if(!*(unsigned char*)(login+0x3450)){
  struct String sid={{0,0,0}};
  ((void(*)(void*,const char*,usize))(base+0x2f3877c))(&sid,(const char*)data,in.sid_size);
  ((void(*)(uptr,const void*))(base+0x7e1573c))(login,&sid);release_string(base,&sid);
 }
 if(!string_equal((const void*)(login+0x3450),data,in.sid_size))return -3;
 if(!(((u32(*)(u32))(base+0x822faa0))(1)&1))return -4;
 struct String keyword=((struct String(*)(void))(base+0x7e46844))();
 int keyword_ok=string_equal(&keyword,data+in.sid_size+in.category_size,in.keyword_size);release_string(base,&keyword);
 if(!keyword_ok)return -5;
 u32 mode=36;
 for(u32 i=0;i<36;i++){
  const unsigned char *name=((const unsigned char*(*)(u32))(base+0x7b8d648))(i);
  if(!name)continue;
  usize n=0;while(n<=64&&name[n])n++;
  if(n==in.category_size&&equal(name,data+in.sid_size,n)){mode=i;break;}
 }
 if(mode==36)return -6;
 uptr root=((uptr(*)(u32))(base+0x7a87db8))(1);
 uptr model=root?*(uptr*)(root+0x20):0;
 if(!model)return -7;
 u64 old_id=((u64(*)(uptr))(base+0x7a5934c))(model);
 if(old_id&&old_id!=in.game_id)return -8;
 ((void(*)(uptr,u64))(base+0x7a59344))(model,in.game_id);
 unsigned char *owned=calloc(1,0xa48);if(!owned)return -9;
 uptr current=((uptr(*)(uptr))(base+0x7a58998))(model);
 ((void(*)(void*))(base+0x7a579bc))(owned);
 ((void(*)(void*,const void*))(base+0x7a58894))(owned,(const void*)current);
 *(u32*)(owned+0x18)=mode; /* Actual category, resolved through original API. */
 ((void(*)(void*,const void*))(base+0x7a58894))((void*)current,owned);
 ((void(*)(void*))(base+0x7a57b1c))(owned);
 for(u32 i=0;i<4;i++){
  uptr storage=*(uptr*)(owned+0x30+i*0x18);
  if(storage)((void(*)(uptr))(base+0x2f376d4))(storage);
 }
 free(owned);
 if(((u64(*)(uptr))(base+0x7a5934c))(model)!=in.game_id||*(u32*)(current+0x18)!=mode)return -10;
 dprintf(2,"ORIGINAL_LIVE_MATCH_CONTEXT account_verified=1 game_id_verified=1 category_enum=%u\n",mode);
 return 1;
}

__attribute__((visibility("default")))
long peerlink_verify_original_start_input(uptr base,uptr command,const char *path){
 struct Input in;unsigned char data[584];if(!base||!command||!path||!load(path,&in,data))return -1;
 if(*(u32*)(command+0x70)!=2||*(unsigned char*)(command+0x88)||*(unsigned char*)(command+0xf8)||*(u32*)(command+0x1d0))return -2;
 uptr request=*(uptr*)(command+0x318);
 if(!request||*(uptr*)request!=base+0x98e6b88)return -3;
 if(*(u64*)(request+0x200)!=in.game_id||*(u32*)(request+0x238)!=in.team_id)return -4;
 if(!string_equal((const void*)(request+0x1e8),data+in.sid_size,in.category_size))return -5;
 for(usize offset=0x208;offset<=0x220;offset+=0x18){
  uptr begin=*(uptr*)(request+offset),end=*(uptr*)(request+offset+8);
  if(!begin||end<begin||(end-begin)%16||(end-begin)/16<11||(end-begin)/16>40)return -6;
 }
 return 1;
}

__attribute__((visibility("default")))
uptr peerlink_original_game_root(uptr base){
 if(!base)return 0;
 uptr result=0;
 for(u32 group=0;group<2;group++){
  uptr manager=((uptr(*)(u32))(base+0x822933c))(group);
  if(!manager)manager=((uptr(*)(u32))(base+0x8229228))(group);
  if(!manager)return 0;
  for(u32 i=0;i<4;i++){
   uptr root=*(uptr*)(manager+0x118+i*8);
   if(!root){
    root=((uptr(*)(usize))(base+0x8c1bbd0))(0x70);
    if(!root)return 0;
    ((void(*)(uptr,const char*,u32))(base+0x822a5c8))(root,"HeadlessRuntimeRoot",group);
    ((void(*)(uptr,u32,uptr))(base+0x82296c8))(manager,i,root);
   }
   if(group==1&&i==0)result=root;
  }
 }
 return result;
}

__attribute__((visibility("default")))
long peerlink_original_game_tree_tick(uptr base,uptr root){
 if(!base||!root||*(uptr*)root!=base+0x997ca90)return 0;
 uptr manager=((uptr(*)(u32))(base+0x822933c))(1);
 if(!manager||*(uptr*)(manager+0x118)!=root||*(uptr*)(root+8)!=manager)return 0;
 ((void(*)(uptr))(base+0x8229554))(manager);
 return 1;
}
