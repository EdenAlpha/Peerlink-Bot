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

The current connection-test APK is `b02d858`, published as
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

Current APK SHA-256:
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
