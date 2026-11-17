# Lobby rule application candidate

The physical 5.0.20 report records the original state-check disagreement at
frame 54 (bot 30497, peer 28974), first disagreement at frame 27, then the
original local stop request at frame 56. Transport failure followed later.
Two other attempts in the report were terminated by Android for low memory.
These are separate failures.

The standalone setup constructs a fresh GameInfo input with an empty key and
copies only participants, category and live server context into it. It does
not import the room's full `match_env`. The native commissioner resets the
active CommonWork settings. Original `7a589a0` applies GameInfo rules only for
a populated supported input with a nonempty key; changing a stored time field
alone did not change the active rules in a controlled Android test.

Original lobby builder `8131aa0` supplies the key `lobby`, converts the room's
native environment through `7a5b760`, and uses `7a587f0` to set GameInfo. Native
environment conversion `7a5a958` consumes the parsed server token/int layout
produced by `7578bc8`. `7a589a0` applies the resulting inputs through original
CommonWork setters, including `8293d88` for active match time.

The candidate carries actual `CMD_GET_ROOM_INFO` rules through production IPC,
uses those original converters and setters after commissioner construction,
and checks that active match time equals the server input before entering the
pre-match flow. Rules are exported as `ROOM_MATCH_RULES`; import failure is a
specific startup failure. It does not assign match-progress or network flags.
The source game remains pinned to 11.1.1, SHA-256
`2cea0c85344d7d7f295aea1c2353621f02db78dbccd888a0a21a0508ac330659`.

## Exact packaged Android verification

CI [44078332691](https://github.com/EdenAlpha/Peerlink-Bot/actions/runs/44078332691)
passed the JVM tests, native lifecycle/diagnostic checks, and Android API 34/35
host tests for source `8754989f032ae38767ae1f0deaf7474526b5a982`.
Artifact `14325464334` was installed directly on the owned ARM64 Android 15 lab.
The actually installed APK was independently hashed for both Android users:
`518b9757af1aae8cf39a62c661ca076d535107ca4df0f4ba3d1ea66fe5b2f29a`
(36,176,203 bytes). The instrumentation APK SHA-256 was
`0d481d6a4b7310c96bef0ceb3d024816604dfe156126b8b0c664346dda31ba43`.

The positive test uses the production room creation preset and the actual
canonical `CMD_GET_ROOM_INFO` environment returned for a fresh owned match.
The room's time is `1`. Both engines report the native input and active time
as `1` after the original rule application succeeds. Each test collected 120
samples with all 22 players, reached kickoff, and observed 33 distinct ball
positions. Automatic home Pass input and the absence of away automatic inputs
were verified. No original mismatch stop occurred. The read-only monitor
retained 43 matching exchanged checker entries through frame 1161. The
original engine's ongoing acceptance, rather than matching ring entries
alone, is the evidence that local simulations remained compatible.

Only the rendered reference peer has the 63 additional packs. The headless
bot has none; no additional game download is needed for this test. Both peers
are PeerLink-hosted original engines. This is **not** a foreground stock-game
match on the user's physical Infinix phone. See [positive-match.json](positive-match.json).

## Deliberate wrong-time control

The same installed APK, native engine, room preset and networking were used
again with fresh owned server authorization. Only the test fixture supplied
the reference peer with time `10`, while the real room and home peer used `1`.
Both native input and active settings confirmed that difference. No debugger,
checksum override, forced readiness flag or game-progress write was used.

The original checker disagreed first at frame 27, confirmed at frame 54,
requested a stop at frame 55 and applied it at frame 56. This reproduces the
physical report's first/confirmation frames and original synchronized-stop
mechanism (the physical request/application were frames 56/57). The actual
native comparison callbacks report different local and peer values on both
sides. A ring monitor can select an exchanged peer-origin stream, so matching
ring entries in this negative control do not establish matching local state.
See [wrong-time-control.json](wrong-time-control.json).

## Restored matching rules

A third fresh owned match restored both native input and active match time to
`1`, using the same installed APK without any source, package or routing
change. Both 120-sample harnesses passed again, with kickoff, automatic home
Pass and ball motion (28 distinct positions per side). There were no mismatch
callbacks, synchronized stops or native/Java crashes. The monitor retained
42 equal exchanged checker entries through frame 1134, with the same caveat
about ring entries described above. Both actually installed APK hashes were
verified again. See [restored-match.json](restored-match.json).

This demonstrates that the omitted room rule application can produce the
observed failure mechanism. Compatibility with the user's foreground stock
game still requires a physical-phone match. Android low-memory process kills
are a separate failure mode and are not claimed fixed by this rule change.

## Published test APK

[Download the exact tested APK](https://github.com/EdenAlpha/Peerlink-Bot/releases/download/phone-engine-test-8754989/peerlink-debug.apk) · [Release page](https://github.com/EdenAlpha/Peerlink-Bot/releases/tag/phone-engine-test-8754989).
Publish run [44081833022](https://github.com/EdenAlpha/Peerlink-Bot/actions/runs/44081833022)
reused CI artifact `14325464334` without a rebuild. GitHub's published asset
digest and size match the APK independently hashed in both test Android users.
See [published-release.json](published-release.json).

## Physical follow-up: room rules alone did not resolve the disconnect

Report1794918503352 from the physical Infinix running5.0.21/source8754989
confirmed requested/input/active match time1, but still recorded first
disagreement27, confirmation54 (local28620,peer28948), stop requested56 and
applied58. The bot stayed alive and packets continued. This invalidates any
interpretation of the previous lab result as a complete physical-phone fix.
See [the subsequent setup exchange investigation](../phone-setup-sync-20261117/README.md).
