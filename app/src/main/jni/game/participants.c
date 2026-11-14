/* Participant IDs supplied from the current server-selected room.
 * Other profile fields retain native defaults until TaskSyncUserData runs. */
typedef __SIZE_TYPE__ size_t; typedef __UINTPTR_TYPE__ uintptr_t; typedef unsigned int u32;
extern void *calloc(size_t,size_t); extern void free(void*);
__attribute__((visibility("default")))
u32 peerlink_lab_uid_participants(uintptr_t base, u32 host, u32 away, u32 self)
{
    if (!base || !host || !away || host == away || (self != host && self != away)) return 0;
    typedef uintptr_t (*Get)(void);
    typedef void (*Construct)(void *);
    uintptr_t root = ((Get)(base + 0x7a87db8))();
    if (!root || !*(uintptr_t *)(root + 0x20)) return 0;
    if (!*(uintptr_t *)(root + 0x70)) {
        if (*(uintptr_t *)(root + 0x60) || *(uintptr_t *)(root + 0x68)) return 0;
        ((void (*)(uintptr_t))(base + 0x7a9f080))(root);
    }
    uintptr_t user_model = ((Get)(base + 0x7ac2488))();
    if (!user_model) return 0;
    unsigned char *users = calloc(2, 0x438), *input = calloc(1, 0xa48);
    if (!users || !input) { free(users); free(input); return 0; }
    for (u32 i = 0; i < 2; ++i) {
        ((Construct)(base + 0x7ad6c9c))(users + i * 0x438);
        *(u32 *)(users + i * 0x438 + 0x410) = i ? away : host;
    }
    ((Construct)(base + 0x7a579bc))(input);
    *(u32 *)(input + 0x2c) = self == host ? 0 : 1;
    typedef void (*CopyRange)(void *, const void *, const void *);
    for (u32 i = 0; i < 2; ++i)
        ((CopyRange)(base + 0x776a614))(input + 0x30 + i * 0x18,
                                       users + i * 0x438, users + (i + 1) * 0x438);
    ((void (*)(uintptr_t, const void *, u32))(base + 0x7ac3894))
        (user_model, users + (self == host ? 0 : 0x438), 1);
    ((void (*)(uintptr_t, const void *))(base + 0x7a58894))
        (*(uintptr_t *)(root + 0x20) + 0x18, input);
    /* Original reset destroys copied UserInfo entries and empties the vectors;
     * their storage is still owned by the temporary input and released here. */
    ((Construct)(base + 0x7a57b1c))(input);
    for (u32 i = 0; i < 4; ++i) {
        uintptr_t storage = *(uintptr_t *)(input + 0x30 + i * 0x18);
        if (storage) ((void (*)(uintptr_t))(base + 0x2f376d4))(storage);
    }
    for (u32 i = 0; i < 2; ++i) ((Construct)(base + 0x7ad70ac))(users + i * 0x438);
    free(input); free(users);
    return 1;
}
