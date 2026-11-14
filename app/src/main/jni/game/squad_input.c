/* Owned native inputs and original constructors/copy routines.
 * Team member JSON at 0x813f860 omits the 147-value parameter bank. A local
 * member bank exists at +0x10 (consumer 0x70b71dc), but filling it does not make
 * those values survive the peer metadata codec. The separate cache probe below
 * follows original parameter-cache insertion/lookup APIs. Neither path loads
 * the static game databases or authenticates an account.
 */
typedef __SIZE_TYPE__ size_t;
typedef __UINTPTR_TYPE__ uintptr_t;
typedef unsigned int u32;
typedef unsigned long long u64;
extern void *calloc(size_t, size_t);
extern void free(void *);
extern int open(const char *, int, ...);
extern long read(int, void *, size_t);
extern int close(int);
extern int dprintf(int, const char *, ...);

typedef struct {
    u32 magic, version, account, base_team, count, coach_id;
    u64 coach_serial;
    char name[72], short_name[8];
    u32 order[40], coordinates[2][2][11][3];
    u32 captain, set_pieces[6], overlaps[3], extras[6];
} SquadHeader;
typedef struct { u32 id, uniform; u64 serial; u32 parameters[147], padding; } Player;
_Static_assert(sizeof(SquadHeader) == 864, "squad DTO header");
_Static_assert(sizeof(Player) == 608, "player DTO size");

static int read_all(int fd, void *output, size_t length)
{
    unsigned char *p = output;
    while (length) {
        long n = read(fd, p, length);
        if (n <= 0) return 0;
        p += n;
        length -= (size_t)n;
    }
    return 1;
}

static u32 verify(uintptr_t base, const unsigned char *team, const SquadHeader *s, const Player *players)
{
    if ((*(const u32 *)team >> 14) != s->base_team || *(const u32 *)(team + 0x1ac) != s->count) {
        dprintf(2, "SQUAD_MISMATCH header team=%u/%u players=%u/%u\n", *(const u32 *)team,
                s->base_team, *(const u32 *)(team + 0x1ac), s->count);
        return 0;
    }
    for (u32 i = 0; i < s->count; ++i) {
        const unsigned char *p = team + 0x1b0 + i * 0xc8;
        if (*(const u64 *)p != players[i].id || *(const u64 *)(p + 8) != players[i].id ||
            *(const u64 *)(p + 0x58) != players[i].serial ||
            *(const unsigned short *)(p + 0x48) != players[i].uniform) {
            dprintf(2, "SQUAD_MISMATCH player=%u id=%llu/%u serial=%llu/%llu uniform=%u/%u\n",
                    i, *(const u64 *)(p + 8), players[i].id, *(const u64 *)(p + 0x58), players[i].serial,
                    *(const unsigned short *)(p + 0x48), players[i].uniform);
            return 0;
        }
    }
    for (u32 i = 0; i < 40; ++i)
        if (team[0x110 + i] != s->order[i]) {
            dprintf(2, "SQUAD_MISMATCH order_slot=%u value=%u/%u\n", i, team[0x110 + i], s->order[i]);
            return 0;
        }
    for (u32 variant = 0; variant < 2; ++variant) {
        for (u32 phase = 0; phase < 2; ++phase) {
            const void *formation = team + 0x50 + variant * 0x60 + phase * 0x22;
            for (u32 i = 0; i < 11; ++i) {
                typedef u32 (*GetRole)(const void *, u32);
                typedef const unsigned char *(*GetPosition)(const void *, u32);
                const unsigned char *p = ((GetPosition)(base + 0x82b5f30))(formation, i);
                if (((GetRole)(base + 0x82b5f10))(formation, i) != s->coordinates[variant][phase][i][0] ||
                    p[0] != s->coordinates[variant][phase][i][1] ||
                    p[1] != s->coordinates[variant][phase][i][2]) {
                    dprintf(2, "SQUAD_MISMATCH formation=%u phase=%u slot=%u\n", variant, phase, i);
                    return 0;
                }
            }
        }
    }
    /* Coach IDs use a 16-bit native number. The server card ID includes a
     * category prefix; the native codec and coach lookup (0x823c6ac) retain
     * only this number. Player card numbers instead use their full uint32. */
    u32 coach_number = s->coach_id & 0xffff;
    if (*(const u64 *)(team + 0x148) != coach_number || *(const u64 *)(team + 0x150) != s->coach_id ||
        *(const u64 *)(team + 0x168) != s->coach_serial) {
        dprintf(2, "SQUAD_MISMATCH coach_id=%llu/%u serial=%llu/%llu\n", *(const u64 *)(team + 0x148),
                coach_number, *(const u64 *)(team + 0x168), s->coach_serial);
        return 0;
    }
    return 1;
}

/* Read-only oracle for an already received/decoded native team. The expected
 * DTO is loaded AFTER the native exchange; it is never copied into the engine.
 * This checks metadata only; parameter arrays remain unapplied in the DTO.
 */
__attribute__((visibility("default")))
int peerlink_team_metadata_verify_at(uintptr_t base, uintptr_t team, u32 account, const char *path)
{
    if (!base || !team || !account || !path) return -1;
    int fd = open(path, 0);
    if (fd < 0) return -2;
    SquadHeader header;
    if (!read_all(fd, &header, sizeof(header)) || header.magic != 0x31515350 ||
        header.version != 1 || header.account != account || header.count < 11 || header.count > 40) {
        close(fd); return -3;
    }
    Player *players = calloc(header.count, sizeof(Player));
    if (!players) { close(fd); return -4; }
    unsigned char extra;
    int result = -5;
    if (read_all(fd, players, header.count * sizeof(Player)) && read(fd, &extra, 1) == 0) {
        result = verify(base, (void *)team, &header, players) ? (int)header.count : 0;
        if (result > 0)
            dprintf(2, "RECEIVED_TEAM_METADATA_VERIFIED account=%u players=%u positions=44 parameters_loaded=0\n",
                    account, header.count);
    }
    close(fd); free(players);
    return result;
}

__attribute__((visibility("default")))
int peerlink_squad_import(uintptr_t base, u32 side, u32 account, const char *path)
{
    if (!base || side > 1 || !account || !path) return -1;
    int fd = open(path, 0);
    if (fd < 0) return -2;
    SquadHeader header;
    if (!read_all(fd, &header, sizeof(header)) || header.magic != 0x31515350 ||
        header.version != 1 || header.account != account || header.count < 11 || header.count > 40) {
        close(fd);
        return -3;
    }
    Player *players = calloc(header.count, sizeof(Player));
    if (!players) { close(fd); return -4; }
    unsigned char extra;
    if (!read_all(fd, players, header.count * sizeof(Player)) || read(fd, &extra, 1) != 0) {
        close(fd); free(players); return -5;
    }
    close(fd);
    for (u32 i = 0; i < 40; ++i)
        if (header.order[i] != 255 && header.order[i] >= header.count) { free(players); return -6; }
    for (u32 v = 0; v < 2; ++v)
        for (u32 p = 0; p < 2; ++p)
            for (u32 i = 0; i < 11; ++i)
                if (header.coordinates[v][p][i][0] > 12 || header.coordinates[v][p][i][1] > 255 ||
                    header.coordinates[v][p][i][2] > 255) { free(players); return -7; }
    typedef void (*Construct)(void *);
    typedef void (*SetIdentity)(void *, u64, u64);
    unsigned char *team = calloc(1, 0x2100);
    if (!team) { free(players); return -4; }
    ((Construct)(base + 0x6a9b204))(team);
    ((Construct)(base + 0x6a9b664))(team);
    /* GameTeamId stores the club number in bits 14..31. Construct it using
     * the same original routine as native conversion at 0x8244708. */
    u32 team_identity_flags = 0;
    ((void (*)(void *, const u32 *, const u32 *))(base + 0x6905bcc))
        (team, &team_identity_flags, &header.base_team);
    for (u32 i = 0; i < 69 && header.name[i]; ++i) team[4 + i] = (unsigned char)header.name[i];
    for (u32 i = 0; i < 3 && header.short_name[i]; ++i) team[0x4a + i] = (unsigned char)header.short_name[i];
    ((SetIdentity)(base + 0x68f8454))(team + 0x148, header.coach_id & 0xffff, header.coach_id);
    *(u64 *)(team + 0x168) = header.coach_serial;
    for (u32 i = 0; i < header.count; ++i) {
        unsigned char *p = team + 0x1b0 + i * 0xc8;
        /* Native identity is (number, card ID), not (card ID, serial).
         * CmdStartGame reads card ID at +8 and unique serial at +0x58.
         * The peer codec preserves uniqueId separately from its id object. */
        ((SetIdentity)(base + 0x68f8454))(p, players[i].id, players[i].id);
        *(u64 *)(p + 0x58) = players[i].serial;
        *(unsigned short *)(p + 0x48) = (unsigned short)players[i].uniform;
    }
    *(u32 *)(team + 0x1ac) = header.count;
    for (u32 i = 0; i < 40; ++i) team[0x110 + i] = (unsigned char)header.order[i];
    for (u32 v = 0; v < 2; ++v)
        for (u32 phase = 0; phase < 2; ++phase) {
            void *formation = team + 0x50 + v * 0x60 + phase * 0x22;
            for (u32 i = 0; i < 11; ++i) {
                const u32 *input = header.coordinates[v][phase][i];
                typedef void (*SetRole)(void *, u32, u32);
                typedef void (*Position)(void *, u32, u32);
                typedef void (*SetPosition)(void *, u32, const void *);
                unsigned char position[2];
                ((SetRole)(base + 0x82b5edc))(formation, i, input[0]);
                ((Position)(base + 0x70b2fe0))(position, input[1], input[2]);
                ((SetPosition)(base + 0x82b5ef4))(formation, i, position);
            }
        }
    int result = -8;
    if (verify(base, team, &header, players)) {
        typedef uintptr_t (*Get)(void);
        uintptr_t owner = ((Get)(base + 0x82468d4))();
        if (!owner) {
            ((void (*)(void))(base + 0x82468a4))();
            owner = ((Get)(base + 0x82468d4))();
        }
        if (owner && !*(uintptr_t *)(owner + 0x40))
            ((void (*)(uintptr_t, u32))(base + 0x82468e0))(owner, 0);
        uintptr_t work = owner ? *(uintptr_t *)(owner + 0x40) : 0;
        if (work) {
            void *destination = (void *)(work + 0x318e790 + side * 0x2100);
            ((void (*)(void *, const void *))(base + 0x69c3894))(destination, team);
            if (verify(base, destination, &header, players)) {
                result = (int)header.count;
                dprintf(2, "SQUAD_NATIVE_METADATA_VERIFIED players=%u positions=44 order_slots=40 coach_id=%u parameters_loaded=0\n",
                        header.count, header.coach_id);
            }
        }
    }
    free(team);
    free(players);
    return result;
}

/* Diagnostic archived-data input for the native player cache used by the
 * server-data consumers at 0x78258ec and 0x765edf0. This is not a CmdStartGame
 * response, opponent delivery, a CommonWork database load or match readiness.
 * Every record is constructed/keyed/packed by original code, then copied by
 * the original cache API. Verification repeats lookups after ALL insertions,
 * so vector growth and fallback records cannot produce a setter-only pass.
 */
__attribute__((visibility("default")))
int peerlink_player_cache_import(uintptr_t base, u32 account, const char *path)
{
    if (!base || !account || !path) return -1;
    int fd = open(path, 0);
    if (fd < 0) return -2;
    SquadHeader header;
    if (!read_all(fd, &header, sizeof(header)) || header.magic != 0x31515350 ||
        header.version != 1 || header.account != account || header.count < 11 || header.count > 40) {
        close(fd); return -3;
    }
    Player *players = calloc(header.count, sizeof(Player));
    if (!players) { close(fd); return -4; }
    unsigned char extra;
    if (!read_all(fd, players, header.count * sizeof(Player)) || read(fd, &extra, 1) != 0) {
        close(fd); free(players); return -5;
    }
    close(fd);
    typedef uintptr_t (*Get)(void);
    uintptr_t root = ((Get)(base + 0x7a87db8))();
    if (!root) { free(players); return -6; }
    if (!*(uintptr_t *)(root + 0x28)) {
        /* The original initializer owns this entire group. Refuse a partial
         * group instead of replacing existing objects or writing root links. */
        const u32 slots[] = {0x38, 0x40, 0xb8, 0xc0, 0xa8, 0xd0, 0xe0,
                             0x108, 0x130, 0x138, 0x158, 0x168, 0x170};
        for (u32 i = 0; i < sizeof(slots) / sizeof(slots[0]); ++i)
            if (*(uintptr_t *)(root + slots[i])) { free(players); return -7; }
        ((void (*)(uintptr_t))(base + 0x7a9e57c))(root);
    }
    uintptr_t data = *(uintptr_t *)(root + 0x28);
    uintptr_t cache = data ? ((uintptr_t (*)(uintptr_t))(base + 0x7a729d8))(data) : 0;
    if (!cache) { free(players); return -8; }
    unsigned char *record = calloc(1, 0x1d0);
    if (!record) { free(players); return -4; }
    typedef struct { u32 id, padding; u64 serial, extra; } CacheKey;
    typedef struct { u32 id, padding; u64 serial; } Identity;
    _Static_assert(sizeof(CacheKey) == 24, "native player-cache key");
    typedef void (*LoadParameters)(const uintptr_t *, void *, u32);
    typedef void (*Insert)(uintptr_t, const void *, const CacheKey *);
    typedef uintptr_t (*Lookup)(uintptr_t, const CacheKey *);
    typedef Identity (*GetIdentity)(const void *);
    typedef u32 (*GetParameter)(const void *, u32, u32 *);
    for (u32 i = 0; i < header.count; ++i) {
        if (!players[i].id || !players[i].serial) { free(record); free(players); return -9; }
        CacheKey key = {players[i].id, 0, players[i].serial, 0};
        ((void (*)(void *))(base + 0x709cf54))(record);
        ((void (*)(void *, u64, u64))(base + 0x823fb78))(record, key.id, key.serial);
        uintptr_t parameters[3] = {(uintptr_t)players[i].parameters,
                                  (uintptr_t)(players[i].parameters + 147),
                                  (uintptr_t)(players[i].parameters + 147)};
        ((LoadParameters)(base + 0x7bcb1e4))(parameters, record, 0);
        ((Insert)(base + 0x7a7ba6c))(cache, record, &key);
    }
    int result = (int)header.count;
    for (u32 i = 0; i < header.count; ++i) {
        CacheKey key = {players[i].id, 0, players[i].serial, 0};
        uintptr_t found = ((Lookup)(base + 0x7a7be44))(cache, &key);
        if (!found || found == cache + 0x330) { result = -10; break; }
        Identity identity = ((GetIdentity)(base + 0x68fe720))((void *)found);
        if (identity.id != key.id || identity.serial != key.serial) { result = -11; break; }
        for (u32 j = 0; j < 147; ++j) {
            u32 value = 0;
            if (!((GetParameter)(base + 0x82a84d4))((void *)found, j, &value) ||
                value != players[i].parameters[j]) {
                dprintf(2, "PLAYER_CACHE_MISMATCH player=%u parameter=%u value=%u/%u\n",
                        i, j, value, players[i].parameters[j]);
                result = -12; break;
            }
        }
        if (result < 0) break;
        key.extra = 1; /* A distinct key must resolve to the native fallback. */
        if (((Lookup)(base + 0x7a7be44))(cache, &key) != cache + 0x330) {
            result = -13; break;
        }
    }
    if (result > 0)
        dprintf(2, "PLAYER_CACHE_VERIFIED account=%u players=%u parameters_each=147 missing_keys_rejected=%u\n",
                account, header.count, header.count);
    free(record);
    free(players);
    return result;
}
