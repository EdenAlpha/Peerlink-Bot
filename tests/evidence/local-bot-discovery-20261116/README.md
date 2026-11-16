# Bot startup failure before peer traffic

The physical export `peerlink_match_1794852963309.zip` identifies the exact
5.0.16 / `5c1a06b` APK. Its new observers establish:

- All seven socket observers installed (`socketObservers=127`).
- Socket creation: 29 calls, zero failures. Bind: four calls, zero failures.
- DNS: seven calls, zero failures; both SDK address families resolved.
- Connect: seven failures, last errno 101 (`ENETUNREACH`). The older aggregate
  diagnostics do not identify the family/destination of each failed connect.
- Send: 14 calls, zero failures. Three IPv4 attempts, last destination
  `54.64.79.140:3478`; no loopback sends toward the local peer.
- Original SDK phase 7, error 1, thread-stop 1, no IPv4/IPv6 network-I/O holders.
- The VPN probe succeeds and receives 12 game packets on each candidate route.
  The bot has already discarded its I/O, so the ferry cannot deliver them.

## Original code path

Pinned 11.1.1 function `7e00574` creates network I/O (`7dfff9c`) and reads its
original flags (`7e00470`). It then checks `7dc2944`. That check reads the
original network option and requires connectivity bit `0x10` in either family.
On failure, startup writes error 1, releases the network I/O, stops its thread,
and enters phase 7. This is the error observed on the physical phone.

`7dc601c` returns the original discovery state, and `7dd365c` / `7dcb9d0`
derive connectivity from actual STUN responses. The fix does not write that
state, its connectivity bit, or any socket/session/model pointer.

## Earlier APK comparison

The earlier connected APK `825a50a` was restored unchanged, SHA-256
`3a7131763cada935f9572399c058f2f7f4dd1d9cb0a22e974d3a9e961bc3754b`.
In a disposable Android lab, its discovery hostname input was directed to an
owned loopback server. Withholding replies produced three requests per engine,
then phase 7 / error 1 and absent I/O in both engines. Supplying normal STUN
responses instead allowed the original headless SDK and normally discovered
rendered SDK to connect and synchronize their metadata. The APK, executable
code and connection verdicts were not modified for these experiments.

This demonstrates that the old binary also depends on successful discovery;
the current symptom can occur without the intervening match-start changes.
It does not establish which carrier/router/server caused discovery to fail on
the physical phone, or explain every prior phone failure.

## App repair

The private headless engine now starts `BotDiscoveryServer` before SDK Start.
The server binds only `127.0.0.80` / `.81` / `.82`, ports 3478/3479. It uses the
existing STUN parser and response builder, including the original transaction,
XOR mapping, alternate source, RESPONSE-PORT, Konami extension and fingerprint.
The mapping uses the bot's existing virtual identity and actual UDP source port.
The normal game's public-server profile handling is unchanged.

The native host supplies this endpoint through the original SDK's STUN-host
override string at `a5da978`, verifying selection with getter `7e45354` before
SDK Start. `7dffa28` copies that input into original NTL configuration. This
replaces the bot's external discovery dependency with its local virtual network;
the SDK still sends real requests and parses real replies. Authenticated server
TURN configuration, peer introductions, packet forwarding and match flow remain
on their original APIs.

The listener reports its readiness, request count and reply count in exported
startup diagnostics. The engine service closes it with the native host. A
real-datagram unit test verifies mapping, source changes, RESPONSE-PORT and
listener cleanup. Android instrumentation requires actual local replies and
original SDK channel connectivity.

### Packaged source-address correction

The first packaged candidate `f40e39d` failed its lab connection check and was
not published. A passive packet observer showed a real STUN request from the
SDK's physical-address-bound socket to `127.0.0.80:3478`, with attribute
`F000/8`. The responder incorrectly accepted only loopback *sources*, so it
discarded a legitimate local SDK packet. The earlier Python helper had accepted
that same physical source, explaining its successful result.

Candidate `679b29e` accepts loopback or addresses owned by a current local
interface, while retaining loopback-only listener bindings. The added unit test
binds its requesting socket to a physical local IPv4 address and verifies the
real reply. Unknown remote source addresses remain excluded.

## Released APK verification

The final source is `a80521e6025b59aab3271d012fbdbaf481fee6c7` (5.0.17).
Its last change waits for the asynchronous post-send counter update in a test;
application code is identical to `679b29e`. The exact installed main APK from
[CI 43933884415](https://github.com/EdenAlpha/Peerlink-Bot/actions/runs/43933884415),
artifact `14259528550`, is 36,157,215 bytes, SHA-256
`545c197ee7f298f0858ebd8bb12786263142082eebaabe94668d1811990a1b68`.
The retained artifact was published without rebuilding:
[release](https://github.com/EdenAlpha/Peerlink-Bot/releases/tag/phone-engine-test-a80521e).

Both actual SDKs connected. The headless bot verified its original discovery
override and received the app's own local STUN replies, without an external
discovery helper. The original SDK stayed at phase 4, error 0, thread-stop 0,
with IPv4 I/O present. Both sides reached kickoff, with 22 players and a valid
ball, in 37.086 / 54.128 seconds. Both completed 120 samples. Only the home
bot received Pass; it moved the ball, peaking at 13.064 m/s. Both sides finished
with identical ball coordinates. No native failure was recorded.

The bot's 63 extra packs were absent and no extra downloads occurred. The
rendered reference had the full packs. The production native backend and
PeerSlot forwarded the actual SDK traffic after replacing a room listener.
The TUN fixture is a datagram socket pair; this is not the complete physical
game/Android-VPN path. `match-proof.json` retains the sanitized verification.
Unit/native checks and Android 14/15 host checks passed.

The physical-phone repair still requires validation with the released APK.
