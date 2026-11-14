/* Owned inputs from all 44 real shipped database tables. The original
 * database builders parse the unchanged records and set their own completion
 * state. The host does not assign any native loading/completion/match flags. */
typedef __UINTPTR_TYPE__ uptr;
typedef __SIZE_TYPE__ usize;
typedef unsigned int u32;
typedef int i32;
extern void *calloc(usize,usize);
extern void free(void *);
extern void *fopen(const char*,const char*);
extern usize fread(void*,usize,usize,void*);
extern int fclose(void*);
extern int fseek(void*,long,int);
extern long ftell(void*);
extern int snprintf(char*,usize,const char*,...);
extern int dprintf(int,const char*,...);

__attribute__((visibility("default")))
long peerlink_game_database_input(uptr base,const char *directory) {
 if(!base||!directory)return -1;
 typedef uptr (*Get)(void);
 uptr owner=((Get)(base+0x82468d4))();
 if(!owner){((void(*)(void))(base+0x82468a4))();owner=((Get)(base+0x82468d4))();}
 if(owner&&!*(uptr*)(owner+0x40))((void(*)(uptr,u32))(base+0x82468e0))(owner,0);
 uptr work=owner?*(uptr*)(owner+0x40):0;
 if(!work||*(u32*)(work+0x319db64))return -2;
 unsigned char *context=calloc(1,0x40),*outer=calloc(1,0x58);
 uptr *hub=calloc(44,sizeof(uptr));
 if(!context||!outer||!hub){free(context);free(outer);free(hub);return -3;}
 long result=-4;u32 made=0;usize total=0;
 ((void(*)(void*))(base+0x8249248))(context);
 for(u32 kind=0;kind<44;kind++){
  i32 relative=*(const i32*)(base+0xcd5f20+kind*4);
  const char *name=(const char*)(base+0xcd5f20+relative);
  usize length=0;while(length<100&&name[length])length++;
  u32 stride=*(const u32*)(base+0xcd5fd0+kind*4);
  if(length<5||length==100||!stride)goto cleanup;
  char path[512];int written=snprintf(path,sizeof(path),"%s/%.*s.decoded",directory,(int)length-4,name);
  if(written<=0||(usize)written>=sizeof(path))goto cleanup;
  void *f=fopen(path,"rb");if(!f)goto cleanup;
  if(fseek(f,0,2)){fclose(f);goto cleanup;}
  long bytes=ftell(f);
  if(bytes<0||bytes%stride||bytes/stride>200000||fseek(f,0,0)){fclose(f);goto cleanup;}
  void *table=calloc(1,0x40);if(!table){fclose(f);goto cleanup;}
  ((void(*)(void*,const char*,usize))(base+0x8291b90))(table,0,stride);
  hub[kind]=(uptr)table;made++;
  if(bytes){
   void *data=((void*(*)(usize))(base+0x8c1bff0))((usize)bytes);
   if(!data){fclose(f);goto cleanup;}
   *(uptr*)((unsigned char*)table+0x28)=(uptr)data;
   if(fread(data,1,(usize)bytes,f)!=(usize)bytes){fclose(f);goto cleanup;}
  }
  *(u32*)((unsigned char*)table+0x30)=(u32)(bytes/stride);
  total+=(usize)bytes;fclose(f);
 }
 *(uptr*)(context+8)=work;
 *(uptr*)(context+0x10)=(uptr)hub;
 *(uptr*)(outer+0x48)=(uptr)context;
 dprintf(2,"ORIGINAL_DATABASE_INPUT tables=%u decoded_bytes=%zu\n",made,total);
 ((void(*)(void*))(base+0x825a334))(outer);
 u32 stadiums=*(u32*)(work+0x319db64);
 uptr stadium=((uptr(*)(uptr,u32))(base+0x823e524))(work,101);
 dprintf(2,"ORIGINAL_DATABASE_BUILD completed=%u stadiums=%u stadium_101_found=%u\n",context[0x20],stadiums,stadium!=0);
 result=context[0x20]&&stadium?(long)made:-5;
cleanup:
 for(u32 i=0;i<44;i++)if(hub[i]){((void(*)(void*))(base+0x8291bf4))((void*)hub[i]);free((void*)hub[i]);}
 free(hub);free(outer);free(context);return result;
}
