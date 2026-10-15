# Gate flow — the proven run order, mapped to the app

Source of truth: the gatebot bundle (`chain3.py` driver, RUN.txt, README,
POSTROOM_FLOW.md §1–23.23). This file maps each proven step to its app
component. Nothing here is invented; anything not yet ported is marked.

## Proven run order (bundle) → app component

| # | Bundle step | App component | Status |
|---|---|---|---|
| 1 | `gate_client.py` cipher selftest | `bot/GateCodec.selftest()` (msgpack+AES+sign+s_keyword regressions) | ported |
| 2 | `mint.py` — mint bot identity via captured `CMD_CREATE_USER` (replay → dedupe retries → fresh user_name/device_identifier), carry `auth_code/user_code/entry_info`, **verify via CMD_LOGIN** before saving | identity store + login verify (`BotIdentity`, `KgsGate.login`); minting itself (CREATE_USER replay) | store ported; mint **NOT ported** |
| 3 | `stun_keepalive.py` — T7 keepalive both ports *(owner :44000, bot :44001)*, every ~5 s, **both uids**; bind-time `stun_mapped()` → advertise the measured reflexive | `bot/T7Keepalive` + `bot/T7` (0x0001 + 0x9090 → pesam.stun.service.konami.net:3478; MAPPED-ADDRESS 0x0001 parse) | ported (service wiring pending) |
| 4 | `chain3.py --live` — full match: login → relay quality → create room → join → READY_FLAG → TURN/session → phases → goals → `set_result` both → `get_result` | `bot/KgsGate` (login + room create + quality primitive); full match sequence | partial: login+create+quality primitive ported; rest **NOT ported** |
| 5 | relay quality — replayed/real quality list before create | `bot/BotRoomService` (sends only with a real template; notes otherwise) | hook ported; real measurement **NOT ported** |
| 6 | `beat_stream.py` — gRPC CommandStream heartbeat, 15 s, TLS+h2 ALPN grpc-exp, `CommandRequest{id,path:"/",packMode:0}` (packMode 1 → 14 UNAVAILABLE) | — | **NOT ported** |
| 7 | `dtls_hold.py` + `turn_rendezvous.py` + `dtls_openssl.py` — DTLS supervisor channel: GET_TURN_SERVER_LIST → STUN Allocate over TLS (SOFTWARE endpoint) → DTLS 1.2 ECDHE → preamble 317/107/155/155/151/151 → 143 B appdata @ ~1 Hz | — | **NOT ported** |

## Wire facts the port must never violate

- Gate body: `IV(16) ‖ AES-256-CBC(zlib(msgpack(msg)))`; sign cookie =
  `b64(HMAC-SHA256(secret|rand^clock) ‖ clock_le ‖ rand_le)`.
- `s_keyword = CONST ^ client_version` (per-build CONST; 11.0.1 regression
  pinned in `GateCodec.selftest`).
- rqid starts at a random per-process base and increments per request (the
  gate caches responses on rqid). Pacing ~0.4 s.
- T7 keepalive: method **0x0001** + attr 0x9090 — the 0x080B punch form is
  peer-only and is NOT answered by Konami STUN. Both uids beat.
- Reflexive rule: advertise exactly what T7 MAPPED-ADDRESS measured from the
  beating socket (run #30's stale-mapping lesson → uncorrelated → DISCONN_HB).
- NAT declaration shape → label (runs #26 vs #27–#29): translated reflexive
  port + `nat_type 270532787` (variant 268435711) → `sycom:P2P`, no relay;
  `nat_type 1` → `sycom:SC` + relay. Caveat on record: the real client once
  got SC with the same nat_type — the relay-quality chain is the second input.
- The human's game side keeps using PeerLink's existing StunFabricator + fake
  open NAT; the bot side never runs STUN-fabrication — it declares and beats
  on its own.

## Open items (largest first)

1. Full match sequence (`chain3` phases: join/READY_FLAG/TURN/START_GAME/
   set_result/get_result) as `BotMatchFlow`.
2. gRPC CommandStream heartbeat port (`kgs_client` framing + `beat_stream`).
3. DTLS supervisor hold port (rendezvous + DTLS 1.2 + 143 B cadence).
4. Identity minting port (CREATE_USER flow + LOGIN verification at intake).
5. VPN loopback-peer wiring: game's packets → `PeerBotEngine`, replies out as
   the peer identity (addresses pinned from tunnel_capture headers first).
