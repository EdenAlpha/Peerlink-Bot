# PeerLink phone bot test

This is an experimental integration branch, `feat/phone-match-test`. The test
package is `com.peerlink.app.enginetest`, displayed as **PeerLink Test**. It can
be installed beside the existing PeerLink app. Only one of their VPNs can run
at a time.

The engine is restricted to the verified eFootball 11.1.1 ARM64 library,
SHA-256 `2cea0c85344d7d7f295aea1c2353621f02db78dbccd888a0a21a0508ac330659`.
An unknown library is refused before offset-based calls. The bot engine needs
Android 12 or later; the user's Android 15 / Helio G81 / Mali-G52 phone meets
the architecture and GLES requirements. Running both games within its 3.7 GB
RAM remains an on-device measurement, not an established performance claim.

The app reads the installed game's APKs/assets and creates its own working
files. First preparation uses approximately 1.1 GB of additional storage. The
original game files and saves are not modified. No game binaries, assets,
account credentials or session IDs are included in this repository/APK.

## User test

1. Open PeerLink Test and create a bot room. The first preparation copies the
   engine and required assets; progress appears in the bot notification/log.
2. Allow the ordinary Android VPN request if needed. Open eFootball and join
   the displayed room number.
3. Use Ready / To Match in the room. Continue through the team preview and
   the final To Match normally. The bot owns the home side and its own match
   process. Mobile controllers must remain allowed in the room settings.
4. When native match objects and an on-pitch ball are observed, the test sends
   Pass to the bot's own gamepad, up to ten times at six-second intervals.
   **Bot Pass** in the notification requests another input. It never taps or
   injects input into the user's foreground game.
5. Confirm the bot's pass/ball response in eFootball. A packet count,
   connected SDK or constructed match alone does not establish kickoff.
   Stop in the notification ends the bot and its game VPN.

## Implementation and limits

The private `:botengine` process attaches its host through Android's public
`Instrumentation.newActivity` API. The real `NativeActivity.onCreate` owns the
native activity record and callback pipe. Its public surface callbacks receive
an app-owned 320×180 ImageReader surface whose frames are drained. No activity
task is launched on a display and no human input queue is attached. The original
task manager owns match updates. Current
account squad inputs and the server's game/session values are passed to the
recovered native input APIs; no readiness or connectivity flags are assigned.
The controller setting uses original setters after server permission is
checked. The complete server-to-native controller configuration mapping is
not established by that test override.

The local ferry reserves a free port before the server receives its profile.
It carries both raw host-candidate traffic and VPN-wrapped fabricated-address
traffic. Only the original engine generates game-protocol replies. The SDK
receives the server's connecting-start time. Relay-selected sessions stop with
a clear error because this test implements the local P2P route.

This is a simple control test, not a completed autonomous player. Exact action
classification, possession/cursor/orientation decoding, sustained phone memory
use and phone-game kickoff require separate evidence.

## Verification

### Initial pitch-screen stall diagnostics (2026-11-16)

Release [`phone-engine-test-5418dfa`](https://github.com/EdenAlpha/Peerlink-Bot/releases/tag/phone-engine-test-5418dfa)
(5.0.18) captures direct and VPN bot UDP payloads at all four ferry boundaries
in the normal export, and reports the original simulation service's waiting
reason and tick gate. It does not change the working STUN or routing policy.

The user's first 5.0.17 attempt loaded the pitch but never moved the ball,
then failed its match-data connection. Its underlying trigger is unresolved.
The second attempt has a confirmed Android low-memory kill. The third export
ends while the bot is alive; the user had backgrounded eFootball to export.
Those findings must not be merged into a single claimed cause.

The exact diagnostic APK passed CI and a two-engine control/capture test with
automatic home-only Pass, no extra controller probe, 150 samples per role and
30,624 recorded boundary events. Forwarded payloads were unchanged, with no
capture drops or write errors. The bot downloaded no extra packs. This is not
verification of the initial stock-game stall's repair or a complete match.
See [retained observations and APK proof](tests/evidence/phone-match-start-20261116/README.md).

### Current local bot discovery repair (2026-11-16)

Release [`phone-engine-test-a80521e`](https://github.com/EdenAlpha/Peerlink-Bot/releases/tag/phone-engine-test-a80521e)
(5.0.17) handles the bot's STUN discovery on the phone. The 5.0.16 physical
export showed original SDK phase 7 / error 1, successful file/socket/bind/DNS
checks and no local peer sends. Withholding STUN replies reproduces this same
failure in the earlier connected APK. The bot now starts a local responder
before SDK Start and supplies its endpoint through the original SDK input.
Requests from the SDK's physical-address-bound sockets are accepted when the
source address belongs to this phone.

The exact released APK passed local-discovery and channel-connectivity
assertions, reached kickoff in 37.086 / 54.128 seconds, and completed 120 samples
with 22 players on both sides. Only the home bot received Pass; the ball moved
at a peak of 13.064 m/s and both engines finished with matching ball coordinates.
The bot had none of the 63 extra packs and performed zero extra downloads.
Unit/native checks and Android 14/15 host checks passed. The published artifact
is unchanged from the installed and tested APK.

This lab uses the production forwarding backend with a socket-pair TUN and a
rendered reference engine. The new build's actual Infinix/game/Android-VPN
kickoff still requires physical validation. See [diagnosis and retained APK
proof](tests/evidence/local-bot-discovery-20261116/README.md).

### Earlier room-reconnect routing fix (2026-11-16)

Release [`phone-engine-test-4a86b1e`](https://github.com/EdenAlpha/Peerlink-Bot/releases/tag/phone-engine-test-4a86b1e)
fixes two regressions observed in the phone export: the running VPN kept an
earlier room's port, and the direct-route source filter rejected the phone's
other valid local address. Room arming now retargets the native backend to the
actual bound listener port; direct-route validation accepts the game's declared
address and addresses currently owned by the phone.

The exact retained APK passed all three ARM64 routing tests and a two-engine
match through the production native backend after a room-port change. Both
engines reached kickoff, and a bot Pass moved the ball. The bot had none of
the 63 extra packs. This lab uses an owned TLS TURN fixture and a datagram
socket pair instead of the Android TUN device. The physical Infinix export
`peerlink_match_1794845885673.zip` confirms this exact app source and successful
routing probes, but the original SDK's network I/O stays unavailable: 12 packets
arrive from the game and none from the bot. This release has not solved phone
kickoff. See [evidence and build identity](tests/evidence/phone-room-reconnect-20261116/README.md).

### Earlier diagnostic builds

The phone export after `eaf7f2a` shows that peer-record parsing now completes,
but the original bot session and data channels fail before server match setup.
The initiating side and underlying cause are not established by that export.
Two headless engines working together does not verify the full game's peer.

Diagnostic build [`3ba7a82`](https://github.com/EdenAlpha/Peerlink-Bot/releases/tag/phone-engine-test-3ba7a82)
adds independent 100 ms native sampling from peer
introduction onward. State changes and a 5 s heartbeat reach the normal match
log. `FIRST_NATIVE_FAILURE` preserves the first observed error, preceding state,
sampling gap, model reason, sync result, database-check setting and frame count.
Mapped reasons come from original build 11.1.1 model initialization/tick code;
unmapped codes and a generic transport error remain explicitly uncertain.
The driver reports a native failure immediately instead of replacing it with a
later generic connection timeout.

`BOT-FLOW` separates host/VPN routes and counts game reception, sends to the
bot, bot reception and sends toward the game, including packet ages and drop
reasons. A UDP send proves local socket acceptance, not application receipt.
`PEERLINK_STOP` records explicit engine, driver, ferry and VPN shutdown paths.
Service loss requests Android's process exit reason when available. These
records cannot read the full game's private internal error or prove the
disconnect initiator when the original engine only reports a generic failure.
They are diagnostics, not a claimed phone-game connection fix.

App unit tests and Android 14/15 host checks passed in [CI 43686079091](https://github.com/EdenAlpha/Peerlink-Bot/actions/runs/43686079091).
The exact retained APK passed an unhooked ARM64 Android 15 two-engine connection
test and a deliberate peer-stop test: its independent monitor preserved the
native failure in the main-process log while the driver was idle. See
[`phone-first-failure-20261115`](tests/evidence/phone-first-failure-20261115/README.md).

The preceding connection-test APK is `eaf7f2a`, published as
[`phone-engine-test-eaf7f2a`](https://github.com/EdenAlpha/Peerlink-Bot/releases/tag/phone-engine-test-eaf7f2a).
The preceding phone export identified the precise parser error: the server
reported 7 valid bytes inside a decoded 20-byte buffer. The driver required
equal lengths and stopped before the server connection report and GameID.
Original callback `0x7e8f444` passes the declared payload count to the original
native input. PeerLink now follows that contract: it forwards the valid prefix,
ignores unused capacity and zero-length replies, and rejects truncated or
oversized data. Omitted size metadata retains decoded-length handling.

All JVM tests, including eleven peer-record tests, and Android 14/15 host
checks passed in
[Android CI run 43679894976](https://github.com/EdenAlpha/Peerlink-Bot/actions/runs/43679894976).
The new tests exercise 7-in-20 and 19-in-20 replies through the production
MessagePack decoder/shared connection phase, padding deduplication, empty
replies and invalid lengths. Pinned native callback instructions and sanitized
phone evidence are in
[`tests/evidence/phone-peer-record-padding-20261115`](tests/evidence/phone-peer-record-padding-20261115/README.md).
This is parser/phase verification; physical-phone kickoff and visible bot Pass
remain unverified. The original native engine, JNI, controller and ferry are
unchanged by this patch.

Diagnostic APK (`3ba7a82`) SHA-256:
`c4bacacbfb9a05561802aee242bdc3eeeb6ed643b5ea94680d37092a3ca367c6`.

The previous parser-test APK was `0979e4c`, published as
[`phone-engine-test-0979e4c`](https://github.com/EdenAlpha/Peerlink-Bot/releases/tag/phone-engine-test-0979e4c).
It accepted omitted size metadata but still rejected valid padded buffers.
Its historical test evidence is in
[`tests/evidence/phone-peer-record-size-20261115`](tests/evidence/phone-peer-record-size-20261115/README.md).

The previous configuration-test APK was `4389161`, published as
[`phone-engine-test-4389161`](https://github.com/EdenAlpha/Peerlink-Bot/releases/tag/phone-engine-test-4389161).
It supplied original TURN configuration and binary peer connection records,
and waited for asynchronous network-I/O objects before opening the configured
native P2P transport.

That exact packaged APK passed an unhooked two-engine test on ARM64 Android
15: both configured transports connected, channels 14/15 reached state 4,
and both survived the hold. The production-ferry/report-order pair,
startup/controller/ferry checks and unanswered-peer negative check also
passed. Its native test evidence and limits are in
[`tests/evidence/phone-configured-transport-20261115`](tests/evidence/phone-configured-transport-20261115/README.md).
The owned local pair did not allocate TURN or establish fresh Konami
authentication, a physical-phone kickoff, or visible bot Pass response.

Previous configuration-test APK SHA-256:
`adf555320753775c247379732134db998f8774c1d5e0863d730d0bd99c94ae0a`.

The previous connection-report APK was `f1fd6f0`, published as
[`phone-engine-test-f1fd6f0`](https://github.com/EdenAlpha/Peerlink-Bot/releases/tag/phone-engine-test-f1fd6f0).
It allowed the server report and GameID setup to proceed after native transport
confirmation. The physical export from that build confirms the progression, then shows
the data channels remaining closed. Its earlier report-order evidence remains
in [`tests/evidence/phone-connection-report-20261115`](tests/evidence/phone-connection-report-20261115/README.md).

The previous routing-test APK was `b02d858`, published as
[`phone-engine-test-b02d858`](https://github.com/EdenAlpha/Peerlink-Bot/releases/tag/phone-engine-test-b02d858).
It keeps the game's host and reflexive candidates distinct through the local
ferry. Raw host traffic and VPN traffic retain their own return sockets.

The prior APK's merged endpoints failed with the phone's asymmetric NAT
profiles. With the same APK and unchanged NAT words, separate endpoints passed,
including after a 130-second room wait. The new packaged APK then connected
both original engines through the actual production `PeerSlot`: both base
sessions and channels 14/15 reached state 4. Transport, engine/controller
startup and the unanswered-peer checks passed. Evidence and precise test
limits are in
[`tests/evidence/phone-candidate-routes-20261115`](tests/evidence/phone-candidate-routes-20261115/README.md).

Previous routing-fix APK (`b02d858`) SHA-256:
`74bbb1e91e64e0409680c05308e270815cdcd4d9f2aed7494aa566ccce5f3ae6`.

The previous connection-test APK was `3ddc561`.
That build corrected the ordering between the base session, original online-model
construction and data-channel readiness. The native guard rejects model
construction until the base session is connected; data channels are then
opened by the original GameDataConnection before later game-start inputs.

The exact packaged APK passed a local two-engine test on ARM64 Android 15.
Separate Android app instances exchanged original SDK packets, both base
sessions connected, both channels 14/15 reached native state 4, and both
connections survived the five-second hold. The no-peer rejection, ferry,
database/controller startup and 15-second startup hold also passed. No hooks
or connection-state writes were used. Owned squad DTOs were staged for this
explicit local input fixture; it supplied no authenticated match ID and does
not verify kickoff against the user's game. Filtered logs and scope are in
[`tests/evidence/phone-channels-20261114`](tests/evidence/phone-channels-20261114/README.md).

Previous channel-order APK (`3ddc561`) SHA-256:
`087fdfec25efd70cc4630db693c88da93c49a2ad11336b75e38d3bfdf76d4899`.

- APK compilation and JVM readiness tests run in Android CI.
- Genuine Android framework tests check the host and separate gamepad events.
- `BotFerryTransportTest` checks only transport with opaque bytes; it is not a
  substitute for a native connection or match.
- `BotEnginePhoneTest`, explicitly selected with an owner-supplied `botUid`,
  installs/starts the original engine and probes its own controller on ARM64.
  It intentionally does not join a match or claim kickoff.
- `BotEngineChannelsPhoneTest` checks that a still-connecting session cannot
  trigger premature model construction or be reported as a match connection.
- `BotEnginePairPhoneTest` checks original base and data-channel connections
  using two separately running instances and a local UDP forwarding fixture.
- `GameArchiveRegression` compares all 44 decoded tables against an independent
  decode of the same installed archive and rejects malformed containers.

On 2026-11-14, APK commit `25ae176` installed successfully over the previous test
APK, then passed three consecutive tests with freshly cleared app data on
ARM64 Android 15 (Redroid). Each attempt loaded the original
engine and all 44 database tables, detected the bot's own controller, delivered
neutral input through the original native handler, and remained responsive for
the 15-second hold. No diagnostic hooks or manual file staging were used.
The APK/JVM build and seven Android framework, input and buffer tests on each
of Android 14 and 15 also passed in
[Android CI run 43642773105](https://github.com/EdenAlpha/Peerlink-Bot/actions/runs/43642773105).
This is test-device startup evidence; kickoff and visible Pass response against
the user's foreground game still require a real match test.

Direct ARM64 tests with the original library identified and verified these
startup corrections before another full APK build:

- Place the five native pak/IoStore files in Android's actual private UE4Game
  directory. The original failed `global.utoc` stat returned `ENOENT`.
- Use PeerLink's real package identity for Android services and PendingIntents.
- Run the original CPU/GPU information initialization before native startup.
- Expose the own software gamepad through the exact JNI input-provider class
  name, without changing Android's global input inventory.
- Wait for the original asynchronous database loader to complete before
  importing the decoded tables. Application/session availability precedes
  this completion; importing during its club-update callback caused an
  intermittent null stadium lookup. The host observes the original completion
  state and releases its task lock while waiting.
- Accept the producer's native frame format in the offscreen consumer. The
  Infinix crash report identified an RGB888 (`0x3`) frame reaching a reader
  fixed to RGBA8888 (`0x1`). A direct Android test reproduced that exact
  exception. The production `PRIVATE` reader, with default consumer usage,
  drained eight frames in each of RGB888, RGBX8888 and RGBA8888. It releases
  buffers without reading pixels or requiring texture sampling.

Match-log exports include `java_crash_report.txt` when a saved Java crash
report exists, so asynchronous frame-consumer failures are retained alongside
the session log. The report's timestamp identifies when the exception occurred.

Previous RGB-fix APK (`25ae176`) SHA-256:
`f79ff4c087673e2755ac84c8e83e90abc8fbaa9641377fb81ab07a769c5a0386`.

### Installed-game file audit

Before startup, PeerLink now streams SHA-256 comparisons between the installed game's library/assets and its private copies. It no longer trusts cache markers or equal file sizes. Missing or mismatched copies are rebuilt from the installed source, checked before atomic replacement, and identified by name in `[BOT-FILES]` export entries. Correct copies are reused without rewriting. The audit covers the ARM64 libraries, selected CPKs, all five packaged match resources and their actual native paths, plus every decoded database table. The 44 tables required by the native database builder are checked against the original pinned engine's record strides; the archive's three additional tables retain their validated decoding without inventing a native record layout. The library still must match the supported 11.1.1 SHA-256.

`SUMMARY result=PASS` means those copies/decodings passed, not that the bot has all data used by the running game. Downloaded/private game updates are explicitly `NOT_CHECKED`. A good copy can still be older than live data downloaded into the game's private storage. This audit does not assert the cause of the phone disconnect or claim kickoff works.

`GameFileIntegrityPhoneTest` deliberately changes one byte in a cached database and one native resource, preserving their sizes; it checks both are detected and restored from the original sources. The standalone `GameArchiveRegression` compares all 44 decoded tables against independent lab output.

Verified file-audit build: [phone-engine-test-21929d2](https://github.com/EdenAlpha/Peerlink-Bot/releases/tag/phone-engine-test-21929d2). Exact APK, Android corruption repair, native pair and controlled-disconnect evidence: [phone-file-audit-20261115](tests/evidence/phone-file-audit-20261115/README.md).

### Native database-revision policy

The physical-phone export `peerlink_match_1794741143595.zip` reached successful connection reporting, a server game ID, both native match-data channels, and accepted match setup. Its first native failure was reason 8 / synchronization result 4: player/coach database checksum comparison. The ferry still forwarded traffic, with no recorded drops and no local stop request.

The original server-environment parser at `07740314` reads `is_revision_check` into response field `+990`. Its normal completion handler `0773d85c` compares that string with the original YES enumeration and stores the result in services `+10` → configuration `+4dc`. Model constructor `07a1df90` consumes that flag; validator `07a2131c` performs the checksum comparison when enabled. The standalone phone host previously kept the constructor's default enabled policy.

The driver now fetches current `CMD_GET_SERVER_ENV` settings, requires an explicit successful YES/NO response, and copies that server policy into its configuration before introduction or model creation. Missing/invalid settings fail explicitly. The native model's checksums, phase, synchronization result and connection flags are never changed by this adapter. A policy change after introduction/model creation is refused. This addresses the confirmed missing startup setting; a fresh physical-phone match is still required to establish whether it clears the observed failure.

The startup file audit is retained separately and exported as `game_file_audit.txt`, with timestamps and an explicit note that an engine may reuse a previous audit. The latest phone export contained no startup audit because the engine was already running when its session log began. Native failures now remain visible in the status text; first-failure diagnostics also include bounded player/coach checksum strings when available.

The exact APK from `f795269` passed app unit tests, Android 14/15 host checks,
and ARM64 Android 15 pair tests with both YES and NO policy inputs. Both native
models inherited each selected setting, synchronized metadata and refused policy
changes during the match. Controlled peer-stop diagnostics and retention of the
file audit after a log reset also passed. See
[`phone-revision-policy-20261115`](tests/evidence/phone-revision-policy-20261115/README.md).
These tests used matching databases and do not establish real-game kickoff.

Current test release:
[`phone-engine-test-f795269`](https://github.com/EdenAlpha/Peerlink-Bot/releases/tag/phone-engine-test-f795269).
APK SHA-256:
`f3c7fc7251c9ac64d3bee56448835a40813ba6fcbd23c3c284b5917b723425ca`.
