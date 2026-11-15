# Original configured transport on ARM64 Android 15

APK commit `4389161` passed Android CI run [43672803569](https://github.com/EdenAlpha/Peerlink-Bot/actions/runs/43672803569), including JVM tests and Android 14/15 framework tests. The exact retained APK was installed in ARM64 Android 15 lab run [43665954307](https://github.com/EdenAlpha/peerlink-efootball-disconnects/actions/runs/43665954307). Its hash and artifact identity are in `build.json`.

## Failure and correction

The latest physical-phone export reached original SDK verdict 1, native base-session state 4 and a server-issued GameID; original match-data channels stayed closed and the model later reported `-201`. Engine frames continued advancing. This did not establish a rendering crash or a rejected server connection report.

The old host omitted authenticated `CMD_GET_TURN_SERVER_LIST` setup and announced an ASCII IP/port in place of the original native binary connection record. GET results were discarded, and WATCH records were logged without being supplied to the engine. An earlier passing two-engine test selected the engine's fallback transport, so it did not cover its configured transport.

The driver now requests TURN configuration using the original account parameter/time-limit getters, imports endpoint rows and credentials/certificate through the original native response callbacks, and carries the original binary connection records unchanged in both directions. Neither those records nor their credentials are treated as ferry endpoint strings. Binary fidelity, validation, GET/WATCH delivery and retry behavior have JVM coverage.

Direct tracing also identified an asynchronous startup race: the configured transport requires original network-I/O handles that do not exist when SDK Start first returns. The driver waits for the exact pointer chains exposed by original getters `0x7e02930`/`0x7e029c4`. It then enters the original session provider. The original TaskMatchSession P2P branch keeps discovery active; the final implementation does not stop it or assign any engine readiness flags.

## Exact packaged APK results

- Two separately running original engines used the owned local TURN configuration and selected native strategy kind 2, rather than the earlier fallback kind 1.
- Role A used the production PeerSlot ferry and distinct host/reflexive sockets. The peers retained the phone's asymmetric NAT words, `271581328` and `270532787`.
- Both native base sessions reached 4; both original data channels 14 and 15 reached 4; both survived the three-second hold.
- The old production-ferry/report-order pair still passed, including the counterpart that delays model construction until connection reporting.
- Ferry/startup/controller checks and the unanswered-peer negative check passed on the same installed APK.
- The final processes were freshly started without diagnostic hooks. No game code, completion flags, NAT words or packet payloads were patched.

This was a direct P2P connection: neither engine allocated TURN or published a relay connection record (`localBytes=0` on both sides). The owned certificate/configuration fixture is not evidence of a TLS relay exchange or fresh Konami authentication. Native binary-record exchange against the real server, physical Infinix kickoff and visible bot Pass response still require a phone match test. This test supplied no authenticated game/session ID and deliberately did not claim kickoff.

The diagnostic experiment that preceded the build is not included in the packaged proof. The retained logs here are from the final exact APK; repeated pending-I/O samples are collapsed in the logcat export. No credentials, raw packet dumps or authenticated session values are included.
