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
  StunFabricator keeps lying to it, untouched); the game punches the bot at
  `197.210.53.2:1162`. At the gate the bot declares a TRANSLATED endpoint --
  host = this phone's real LAN IP, reflexive = `.53.2:1162` (same shape the
  real games declare in the flipped peer rooms). The bot never runs the
  fabricator. (2026-10-15 night: declaring host .53.2 -- an address that
  exists nowhere on the wire -- got `ERR_EMPTY` x7 on `match_ready`, the
  empty endpoint record. Run #21's host == reflexive == real egress was
  accepted, so the rule is the host must be owned, not that it must
  differ.)
- Peer slot fills locally: traffic the game sends to `.53.2` goes to the bot
  engine instead of the tunnel to another phone; the bot's replies are
  injected back wearing the peer fabricated address (the existing path).
- Bot lifecycle: room create -> T7 beats (own socket, outside the per-app
  VPN, local bind `:44001`) -> `PeerBotService` foreground lifeline (the
  bot session must survive the user living in eFootball) + per-app VPN
  start. A FRESH INSTALL has no VPN consent yet: the create-room flow
  raises the one-time system dialog (`ACTION_VPN_CONSENT` -> MainActivity),
  never silently skipping the VPN. Then `BotMatchDriver` -- state-driven:
  seat poll -> entry side -> relay-quality report (room-time, capture
  block 63 -- every NOERR-ready session on record SETs it first) ->
  enable-check (OTZP- tolerated) -> ready
  (translated profile: real LAN host / fabricated reflexive) -> go poll -> session -> capture block-105 check-res
  echo -> game_id -> session id -> TURN announce/watch -> antenna, then
  periodic antenna beats. Driver v2 (2026-10-15 late -- the user's game sat
  on "Waiting for Opponent" because v1 could give up silently): the seat
  watch has NO deadline, every rung retries transport noise
  (`_NET_FAIL`/`_HTTP_EMPTY`/`_DECODE_FAIL`) and `ERR_ALREADY_*` counts as
  done, the ready is re-asserted every 20 s while waiting, the peer slot is
  armed the moment `go_match` fires and re-targeted when the session reply
  carries the game's address, and every rung narrates to the notification.
  Connection-phase responder + native peer sink = `PeerSlot` -- **LIVE**:
  the native backend's diverted `.53.2` frames land on the slot's loopback
  socket (next bullet), which answers with the byte-exact forms
  (PeerBotEngine) and returns complete inner IP packets toward the game. The
  game's address comes
  AUTOMATICALLY from the session's `ntl_address_list` (host half = delivery
  target, reflexive half = what the game announced); the bot punches FIRST
  and retries until the crossing, then the session + capability burst
  (2026-10-13 capture order). The first inbound packet refreshes the
  address (ground truth beats declaration).
- Bot VPN datapath (final, user-corrected): the SAME native backend peer
  mode uses owns the TUN and passes everything through (login, Konami, DNS);
  only the game's fabricated-IP UDP frames are diverted -- and the native
  "peer" is pointed at the loopback PeerSlot (`peerLanIp=127.0.0.1`,
  `peerPort=1162`). The slot answers in the native wire format (header-less
  replies are accepted as raw payload; 0xFC probe -> 0xFD; 0xFF keepalive
  ignored) and the native injects the replies into the game. Underlay = the
  system default network (Wi-Fi OR mobile, `resolveDefaultUnderlay`); no
  LAN-path lock, no peer-link probe. Two earlier attempts that day (a legacy
  Kotlin engine datapath) died at birth and black-holed the game; both are
  removed. A fresh install still needs the one-time VPN consent dialog.
- SQUAD BOOTSTRAP (T5 live proof, 2026-10-15): a freshly minted account has
  NO squad (`GET_USER_SQUAD` -> `ERR_NOT_ENOUGH_PLAYER_IN_SQUAD`) and its
  ready is refused `ERR_EMPTY`/PNPS- regardless of endpoints or quality
  reports -- this, not the address and not the quality, is what the 22:42 /
  23:53 / 00:19 device runs hit. The mint now pushes
  `CMD_SET_MYCLUB_ENTRY_INFO` (captured template + the account's own
  entry_info) on its verify session, and `BotRoomService` self-heals an
  older squad-less account by minting a replacement automatically. Proven
  end-to-end on the live gate: fresh+mint+squad = ready NOERR, `go_match`
  YES.
- Peer mode untouched: pair still computed from both phones' LANs,
  `verifyPeerPath`/LAN lock intact.
- Declared vs measured: bot mode declares a translated endpoint (real LAN
  host, fabricated .53.2 reflexive -- the address the game punches), NOT a
  measured STUN mapping; `KgsGate.createRoom` now refuses an empty endpoint
  outright (the old `undeclared()` path could PUT blank addresses).

## Open items (largest first)

1. Full match sequence: the ladder is **ported** (`bot/BotMatchFlow`) and the
   driver walks it (`bot/BotMatchDriver`). Remaining: `SquadSource`
   (23-player lists w/ `parameters` for the SGR checksums, from
   `CMD_GET_USER_SQUAD`).
2. gRPC CommandStream heartbeat port (`kgs_client` framing + `beat_stream`).
3. DTLS supervisor hold port (rendezvous + DTLS 1.2 + 143 B cadence).
4. ~~Identity minting port~~ **done**: `BotMinter` auto-mints + verifies on
   first room create. Still open: carrying `entry_info` (the 23-player roster
   from the CREATE reply) for SET_MYCLUB_ENTRY_INFO.
5. VPN loopback-peer wiring: game's packets → `PeerBotEngine`, replies out as
   the peer identity (addresses pinned from tunnel_capture headers first).
