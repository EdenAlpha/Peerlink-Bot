# Distinct host and reflexive routes

Production APK source: `b02d858b2987c4db48ae0b6f8e76c041b70518eb`.
[Android CI 43661287221](https://github.com/EdenAlpha/Peerlink-Bot/actions/runs/43661287221).

The phone export contained 22 native frames, successful room/session setup,
native base-session state 1 followed by 6, and a bot capability reply with
result `0x00040001`. Both data channels remained unconnected. There was no
reported packet-capture loss or new Java crash report. The packet metadata in
`phone-capture-summary.json` omits the user's addresses and account IDs.

PeerLink supplied the original SDK with one loopback endpoint for both of the
opponent's IPv4 candidates while retaining the translated NAT profile. Its
ferry also selected the reply route using a global last-received-path value.
Host and reflexive identities could therefore collapse or change paths.

The regression experiment used the exact previous APK, SHA-256
`087fdfec25efd70cc4630db693c88da93c49a2ad11336b75e38d3bfdf76d4899`:

| Local native test | Merged candidates | Separate candidates |
| --- | --- | --- |
| Earlier symmetric NAT fixture | Connected | Connected |
| Phone peer profiles: 271581328 / 270532787 | Failed, native session 6 | Both sessions and data channels connected |
| Same profiles after a 130-second room wait | Not run | Both sessions and data channels connected |

Only the test's endpoint translation changed between those experiments. The
original library, server-profile NAT words and native packet payloads were
preserved. The local merged-candidate failure reproduces the connection
failure; it does not claim to reproduce the phone's exact capability-refusal
packet. No successful connection or completion fields were assigned.

The production fix supplies separate host/reflexive loopback endpoints. Raw
host-candidate traffic uses the host socket in both directions. VPN-carried
traffic uses the reflexive socket in both directions. One candidate's traffic
does not overwrite the other's return path. Both routes carry original SDK
payloads unchanged.

The exact new APK was installed as an update and tested on ARM64 Android 15
Redroid with two app instances in separate Android users. `ProductionPairFerry`
drives the actual production `PeerSlot`, wrapping the other original SDK's
payloads only in the VPN's outer IPv4/UDP carrier. Both original base sessions
and channels 14/15 reached state 4 and remained connected during the five-second
hold. Both `PRODUCTION_FERRY_*_ORIGINAL_CHANNELS_CONNECTED` markers are retained
in `production/`.

The transport regression also checks that receiving a VPN packet does not
redirect the host candidate's replies. Original-engine startup, all 44 database
tables, own-controller detection, the 15-second startup hold and the unanswered
peer guard passed. The JVM/APK build and seven framework tests on each of
Android 14 and 15 passed. The retained tests used no diagnostic hooks.

These are local original-engine and production-transport tests using owned
squad DTOs. They supply no fresh authenticated match ID. The user's foreground
game connection, kickoff, visible Pass response and sustained physical-phone
memory use still require a real match test.

New APK SHA-256:
`74bbb1e91e64e0409680c05308e270815cdcd4d9f2aed7494aa566ccce5f3ae6`.
