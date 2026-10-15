# Gate flow — the proven run order, mapped to the app

Source of truth: the gatebot bundle (`chain3.py` driver, RUN.txt, README,
POSTROOM_FLOW.md §1–23.23). This file maps each proven step to its app
component. Nothing here is invented; anything not yet ported is marked.

## Proven run order (bundle) → app component

| # | Bundle step | App component | Status |
|---|---|---|---|
| 1 | `gate_client.py` cipher selftest | `bot/GateCodec.selftest()` (msgpack+AES+sign+s_keyword regressions) | ported |
| 2 | `mint.py` — mint bot identity via captured `CMD_CREATE_USER` (replay → dedupe retries → fresh user_name/device_identifier), carry `auth_code/user_code/entry_info`, **verify via CMD_LOGIN** before saving | `BotMinter` (auto-mints on first room create; captured bodies in `GateCaptures`, decoded at runtime, credentials patched in memory) + proven captured-login replay in `KgsGate.login` | **ported** (entry_info carry: pending) |
| 3 | `stun_keepalive.py` — T7 keepalive both ports *(owner :44000, bot :44001)*, every ~5 s, **both uids**; bind-time `stun_mapped()` → advertise the measured reflexive | `bot/T7Keepalive` + `bot/T7` (0x0001 + 0x9090 → pesam.stun.service.konami.net:3478; MAPPED-ADDRESS 0x0001 parse) | ported (service wiring pending) |
| 4 | `chain3.py --live` — full match: login → relay quality → create room → join → READY_FLAG → TURN/session → phases → goals → `set_result` both → `get_result` | `bot/BotMatchFlow` (join ladder, user/team sync, entry side, ready, session, phase ladder, add score/foul, START_GAME, set_result body + checksum rule, get_result); `bot/KgsGate` primitives | ladder + bodies **ported**; remaining: two-gate driver + `SquadSource` data |
| 5 | relay quality — replayed/real quality list before create | `bot/BotRoomService` (sends only with a real template; notes otherwise) | hook ported; real measurement **NOT ported** |
| 6 | `beat_stream.py` — gRPC CommandStream heartbeat, 15 s, TLS+h2 ALPN grpc-exp, `CommandRequest{id,path:"/",packMode:0}` (packMode 1 → 14 UNAVAILABLE) | — | **NOT ported** |
| 7 | `dtls_hold.py` + `turn_rendezvous.py` + `dtls_openssl.py` — DTLS supervisor channel: GET_TURN_SERVER_LIST → STUN Allocate over TLS (SOFTWARE endpoint) → DTLS 1.2 ECDHE → preamble 317/107/155/155/151/151 → 143 B appdata @ ~1 Hz | — | **NOT ported** |

## Wire facts the port must never violate

- Gate body: `IV(16) ‖ AES-256-CBC(zlib(msgpack(msg)))`; sign cookie =
  `b64(HMAC-SHA256(secret|rand^clock) ‖ clock_le ‖ rand_le)`.
- `s_keyword = CONST ^ client_version` (per-build CONST; 11.0.1 regression
  pinned in `GateCodec.selftest`).
- Login = capture replay (room1.py's path): the committed ciphertext body in
  `GateCaptures` is decoded at runtime and the identity's credentials are
  patched in memory. Every accepted live run logged in exactly this way; the
  from-scratch envelope login was never live-proven.
- Room create MUST be `mode CUSTOM / kind SINGLE`: the search-by-number index
  only lists those. A `STRIKE_ARENA` room answers the game's search with
  `ERR_ROOM_NOT_FOUND` (proven live 2026-10-15, room 4009780 vs 3966797).
- `client_version` is a moving lock. The 2026-10-15 store update (app 11.1.1)
  made the gate answer `ERR_DEFCLIENTVER` (`CIYU-`) for 6.1.0; the same
  request with **6.1.1** logged in `NOERR` — and the 11.1.0 s_keyword CONST
  was still accepted, so the server does NOT enforce the CONST/CV pairing.
  After any store update expect exactly this: bump the one constant, keep
  BUILD until a login actually fails on the keyword.
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

## Bot mode (PeerLink-in-PeerLink) -- locked 2026-10-15

The two-phone P2P flow with the far phone replaced by the local bot:

- Fabricated pair is FIXED in this mode: game = `197.210.53.1` (the existing
  StunFabricator keeps lying to it, untouched), bot = `197.210.53.2:1162`
  declared at the gate. The bot never runs the fabricator.
- Peer slot fills locally: traffic the game sends to `.53.2` goes to the bot
  engine instead of the tunnel to another phone; the bot's replies are
  injected back wearing the peer fabricated address (the existing path).
- Bot lifecycle: room create -> T7 beats (own socket, outside the per-app
  VPN, local bind `:44001`) -> `PeerBotService` foreground lifeline (the
  bot session must survive the user living in eFootball) + per-app VPN
  start. A FRESH INSTALL has no VPN consent yet: the create-room flow
  raises the one-time system dialog (`ACTION_VPN_CONSENT` -> MainActivity),
  never silently skipping the VPN. Then `BotMatchDriver` -- state-driven:
  seat poll -> entry side -> enable-check (OTZP- tolerated) -> ready
  (fabricated profile) -> go poll -> session -> capture block-105 check-res
  echo -> game_id -> session id -> TURN announce/watch -> antenna, then
  periodic antenna beats.
  Connection-phase responder + tunnel sink = `PeerSlot` -- **LIVE**:
  TunnelEngine hands every game packet addressed to `.53.2` to the slot,
  which answers with the byte-exact forms (PeerBotEngine) and injects its
  replies toward the game as the fabricated peer. The game's address comes
  AUTOMATICALLY from the session's `ntl_address_list` (host half = delivery
  target, reflexive half = what the game announced); the bot punches FIRST
  and retries until the crossing, then the session + capability burst
  (2026-10-13 capture order). The first inbound packet refreshes the
  address (ground truth beats declaration).
- Peer mode untouched: pair still computed from both phones' LANs.
- Declared vs measured: bot mode declares the fabricated identity, NOT the
  measured one; `KgsGate.createRoom` now refuses an empty endpoint outright
  (the old `undeclared()` path could PUT blank addresses).

## Open items (largest first)

1. Full match sequence: the ladder itself is **ported** (`bot/BotMatchFlow`).
   Remaining: the driver that walks it with both gates, and `SquadSource`
   (23-player lists w/ `parameters` for the SGR checksums, from
   `CMD_GET_USER_SQUAD`).
2. gRPC CommandStream heartbeat port (`kgs_client` framing + `beat_stream`).
3. DTLS supervisor hold port (rendezvous + DTLS 1.2 + 143 B cadence).
4. ~~Identity minting port~~ **done**: `BotMinter` auto-mints + verifies on
   first room create. Still open: carrying `entry_info` (the 23-player roster
   from the CREATE reply) for SET_MYCLUB_ENTRY_INFO.
5. VPN loopback-peer wiring: game's packets → `PeerBotEngine`, replies out as
   the peer identity (addresses pinned from tunnel_capture headers first).
