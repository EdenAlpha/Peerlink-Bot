/* Pinned 11.1.1 lobby inputs. Original converters, model copy/setter and rule
 * application own all native representations. No match-progress flag is set. */
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

typedef uintptr_t uptr;
typedef uint32_t u32;
struct Header { u32 magic, version, strings, minutes, ball, substitution_times, substitutions; };
_Static_assert(sizeof(struct Header) == 28, "room rule header");
struct Text { u32 length; char data[65]; };
static char diagnostic[512] = "ROOM_MATCH_RULES present=0";
const char *peerlink_room_rules_diagnostics(void) { return diagnostic; }
static long fail(long code, const char *reason) {
    snprintf(diagnostic, sizeof(diagnostic), "ROOM_MATCH_RULES present=1 applied=0 error=%s", reason);
    return code;
}

static int read_input(const char *path, struct Header *header, struct Text text[11]) {
    FILE *file = path ? fopen(path, "rb") : NULL;
    if (!file) return 0;
    int ok = fread(header, 1, sizeof(*header), file) == sizeof(*header) &&
        header->magic == 0x31524c50 && header->version == 1 && header->strings == 11 &&
        header->minutes >= 1 && header->minutes <= 255 && header->ball <= 65535 &&
        header->substitution_times <= 255 && header->substitutions <= 255;
    for (u32 i = 0; ok && i < 11; ++i) {
        ok = fread(&text[i].length, 1, 4, file) == 4 && text[i].length <= 64;
        if (ok) ok = fread(text[i].data, 1, text[i].length, file) == text[i].length;
        if (ok) {
            text[i].data[text[i].length] = 0;
            for (u32 j = 0; j < text[i].length; ++j)
                if ((unsigned char)text[i].data[j] < 32 || (unsigned char)text[i].data[j] > 126) ok = 0;
        }
    }
    if (ok) ok = fgetc(file) == EOF && !ferror(file);
    fclose(file);
    return ok;
}

long peerlink_room_rules_input(uptr base, const char *path) {
    struct Header header;
    struct Text text[11];
    if (!base || !read_input(path, &header, text)) {
        return fail(-1, "invalid_input");
    }
    uptr root = ((uptr (*)(u32))(base + 0x7a87db8))(1);
    uptr model = root ? *(uptr *)(root + 0x20) : 0;
    uptr owner = ((uptr (*)(void))(base + 0x82468d4))();
    uptr work = owner ? *(uptr *)(owner + 0x48) : 0;
    uptr current = model ? ((uptr (*)(uptr))(base + 0x7a58998))(model) : 0;
    if (!current || !work || *(u32 *)(current + 0x2c) > 1) return fail(-2, "native_context_unavailable");
    const char *category = ((const char *(*)(u32))(base + 0x7b8d648))(*(u32 *)(current + 0x18));
    if (!category || strcmp(category, "MYCLUB_LOBBY_ROOM_MATCH_1VS1")) return fail(-3, "unsupported_category");
    unsigned char *input = calloc(1, 0xa48);
    if (!input) return fail(-4, "allocation_failed");
    /* 8131aa0 uses the same native lobby key and 7a5b760 rule conversion. */
    ((void (*)(void *, const void *))(base + 0x73d0694))(input, (const void *)current);
    uptr environment[0x120 / sizeof(uptr)] = {0};
    uptr rules[0x38 / sizeof(uptr)] = {0};
    const unsigned offsets[11] = {0,0x18,0x30,0x50,0x70,0x88,0xa8,0xc0,0xd8,0xf0,0x108};
    for (u32 i = 0; i < 11; ++i)
        ((void (*)(void *, const char *, size_t))(base + 0x2f3877c))
            ((unsigned char *)environment + offsets[i], text[i].data, text[i].length);
    *(u32 *)((unsigned char *)environment + 0x48) = header.minutes;
    *(u32 *)((unsigned char *)environment + 0x68) = header.ball;
    *(u32 *)((unsigned char *)environment + 0xa0) = header.substitution_times;
    *(u32 *)((unsigned char *)environment + 0xa4) = header.substitutions;
    ((void (*)(void *, const void *))(base + 0x7a5a958))(rules, environment);
    ((void (*)(void *, void *))(base + 0x7a5b760))(rules, input + 0x90);
    ((void (*)(void *, const char *, size_t))(base + 0x2f3877c))(input, "lobby", 5);
    if (*(u32 *)(input + 0x2c) == 1) {
        u32 home = *(u32 *)(input + 0xc4);
        *(u32 *)(input + 0xc4) = *(u32 *)(input + 0xc8);
        *(u32 *)(input + 0xc8) = home;
    }
    input[0x9cd] = ((unsigned char *)rules)[0x34];
    input[0x104] = ((unsigned char *)rules)[0x35];
    if (input[0x104] == 1) *(u32 *)(input + 0x108) = 1;
    input[0xa3a] = ((unsigned char *)rules)[0x36];
    ((void (*)(uptr, const void *))(base + 0x7a587f0))(model, input);
    unsigned applied = ((u32 (*)(uptr))(base + 0x7a589a0))(model) & 1;
    unsigned actual_minutes = *(unsigned char *)(work + 0x6c4c0 + 0xb8);
    unsigned expected_minutes = *(u32 *)(input + 0xb0);
    snprintf(diagnostic, sizeof(diagnostic),
        "ROOM_MATCH_RULES present=1 applied=%u lobbyKey=lobby requestedTime=%u inputTime=%u activeTime=%u "
        "cpu=%u injury=%u extraTime=%u penalties=%u ball=%u substitutionTimes=%u substitutions=%u controller=%u",
        applied, header.minutes, expected_minutes, actual_minutes, *((u32 *)rules + 5),
        input[0xb4], input[0xb5], input[0xb6], header.ball, header.substitution_times,
        header.substitutions, input[0xa3a]);
    for (u32 i = 0; i < 11; ++i) {
        unsigned char *s = (unsigned char *)environment + offsets[i];
        if (s[0] & 1) ((void (*)(uptr))(base + 0x2f376d4))(*(uptr *)(s + 16));
    }
    ((void (*)(void *))(base + 0x73d09ec))(input);
    free(input);
    dprintf(2, "%s\n", diagnostic);
    return applied && expected_minutes == header.minutes && actual_minutes == header.minutes ? 1 : -5;
}
