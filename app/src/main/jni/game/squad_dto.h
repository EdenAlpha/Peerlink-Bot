#pragma once
#include <stdint.h>

typedef struct {
    uint32_t magic, version, account, base_team, count, coach_id;
    unsigned long long coach_serial;
    char name[72], short_name[8];
    uint32_t order[40], coordinates[2][2][11][3];
    uint32_t captain, set_pieces[6], overlaps[3], extras[6];
} PeerlinkSquadHeader;

typedef struct {
    uint32_t id, uniform;
    unsigned long long serial;
    uint32_t parameters[147], padding;
} PeerlinkSquadPlayer;

#ifdef __cplusplus
static_assert(sizeof(PeerlinkSquadHeader) == 864, "squad DTO header");
static_assert(sizeof(PeerlinkSquadPlayer) == 608, "squad DTO player");
#else
_Static_assert(sizeof(PeerlinkSquadHeader) == 864, "squad DTO header");
_Static_assert(sizeof(PeerlinkSquadPlayer) == 608, "squad DTO player");
#endif
