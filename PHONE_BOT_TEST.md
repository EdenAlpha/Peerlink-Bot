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

- APK compilation and JVM readiness tests run in Android CI.
- Genuine Android framework tests check the host and separate gamepad events.
- `BotFerryTransportTest` checks only transport with opaque bytes; it is not a
  substitute for a native connection or match.
- `BotEnginePhoneTest`, explicitly selected with an owner-supplied `botUid`,
  installs/starts the original engine and probes its own controller on ARM64.
  It intentionally does not join a match or claim kickoff.
- `GameArchiveRegression` compares all 44 decoded tables against an independent
  decode of the same installed archive and rejects malformed containers.

On 2026-11-14, APK commit `f415515` passed three consecutive tests with freshly
cleared app data on ARM64 Android 14 (Redroid). Each attempt loaded the original
engine and all 44 database tables, detected the bot's own controller, delivered
neutral input through the original native handler, and remained responsive for
the 15-second hold. No diagnostic hooks or manual file staging were used.
The APK/JVM build and five Android framework/input tests also passed in
[Android CI run 43620523688](https://github.com/EdenAlpha/Peerlink-Bot/actions/runs/43620523688).
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

Verified APK SHA-256:
`f5f4f9d36b9f514baf221c882c748950b376a35c4a6aec9b7bedf2d0c767f675`.
