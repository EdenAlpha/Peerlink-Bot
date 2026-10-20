# Engine-driven P2P: what the engine needs, and what the bot must do

This is the specification, distilled from the engine's own code (build B,
`libUE4.so`, sha256 `2cea0c85...330659`) running for real in the lab on this
PC: an arm64 Linux guest with Android 14's own bionic, the engine loaded by
Android's own `linker64`, real threads, real sockets, real UDP on the wire.

The engine's punch is already correct and proven. What follows is what it
still needs, and why.

## 1. The engine's punch is byte-for-byte the phone's punch

`work/qemu/tools/compare_capture.py` compares the engine's own datagrams
with the user's real capture
(`work/plink/user_export_1792148947941/tunnel_capture.pcapng`, 27 frames):

    the capture: 27 frames, all 0x080b, 48 bytes, cookie 2112a442,
      attribute 0x9090 length 24, value
      02 00 00 01 00 00 00 00 + the 16-byte text "1012115771"

    the engine:  10 of 10 punch messages carrying that same player id are
      IDENTICAL in every field (type, length, cookie, attribute type,
      attribute length, attribute value, the 16-byte id text).  Only the
      12-byte transaction id differs, which is different for every
      transaction by design.

The engine also answers the peer's punch by itself (`090b`), with the right
uid and the right transaction id. The bytes on the wire are the engine's.

## 2. Why the engine stops at the punch

The transport adds a peer to the **IPv4 channel and the IPv6 channel**, and
keeps the peer's id only when both succeed
(`FUN_07eee9fc`, the transport's vtable + 0xb0):

    iVar5 = add(family 1) -> id in local_70
    iVar6 = add(family 2) -> id in local_74
    if (iVar5 != 0xe000012 && iVar6 != 0xe000012 && (the two disagree)) {
        drop the peer; id = -1
    }
    *out_id = id;

`0xe000012` means "this family has no channel". The id is what the engine's
own connect path uses to find the peer (the checker matches the entry id
against `peer+4` in `FUN_07ef85b0`), and only then can the job reach the
stage that sends the next packet of the protocol, `0x80a UHP_FINALIZE`
(`FUN_07ef16d4`, stage 1 -> `FUN_07ef86f0`).

Measured in the lab, every run:

    get_channel(family 1) -> a channel            (the view itself)
    get_channel(family 2) -> null                  (no IPv6 channel)
    transport peer id     -> 0
    client working id     -> -1
    checker's return      -> 0
    client state          -> 3 (punching), never 4 (connected)

So: with no IPv6 channel the id is thrown away, the engine looks the peer
up under an id its own transport never used, and the connect dies at the
punch - with zero fabricated frames, because the engine simply never gets
to the next step.

## 3. What the engine needs, precisely

1. **A peer record that says both families are usable.** The record's IPv4
   and IPv6 NAT words must have bit 4 set (the capture's own open-NAT value
   is `0x102000b3` for both). The engine's own code then adds the peer's
   candidate addresses on both families (`FUN_07f00934`).

2. **A channel per family.** A channel for a family exists only once the
   engine has a local address for that family AND its own NAT traversal for
   that family has succeeded. The engine publishes the local address per
   family in `FUN_07ec3858`, which calls the engine's own allocator
   (`FUN_07ec9c84` -> the manager's vtable + 0xd0); the allocator's answer
   has to come back on the engine's own socket.

3. **The local address for the IPv6 family, real.** The engine's own words
   for it are in what it reports: `IPADDR` / `SERVNAME` / `NATTYPE` and
   `IPADDRV6` / `SERVNAMEV6` / `NATTYPEV6`, plus the peer-side ones. In the
   lab the engine's IPv6 list is empty and it falls back to the dummy
   `1400:100::`; on the phone the phone has IPv6, so the engine gets a real
   one by itself.

## 4. What the bot must therefore do (and stop doing)

**Stop**
- The bot must not answer the engine's P2P frames. `PeerSlot` and
  `PeerBotEngine` fabricate the `0x090b` answers, the session frames and the
  capability frames today. That code is removed, not fixed: the engine's own
  exchange is the only one.

**Do**
- The bot keeps the server work: login, the room, the peer entry, the
  approval, half-time, full-time, the score.
- The bot hands the engine the room/peer data, with **both** families: the
  peer's IPv4 and IPv6 address blocks and NAT types come from the server's
  `ntl_address_list` (the server distributes both sides' blocks), and the
  bot's own blocks are the ones it declares in `CMD_JOIN_ROOM` and
  `CMD_SET_ROOM_MATCH_READY`.
- The bot hands the engine what STUN would have told it: the local address,
  port and NAT type for **both** families. The bot already measures the
  IPv4 mapping (`T7Keepalive`, the T7 beat and its MAPPED-ADDRESS); the
  IPv6 half is the same measurement over an IPv6 socket, and the phone has
  IPv6.
- The engine is fed its own id, team and squad so the bot's squad plays and
  the real eFootball on the same phone is the opponent.

## 5. The inert IPv6 block, today

The room and ready messages always send this for IPv6
(`BotMatchFlow.kt`):

    /** Capture's inert IPv6 -- "::" / 0 everywhere (block 98). */
    private val NET6 = linkedMapOf(
        "host_address" to "::", "host_port" to 0,
        "reflexive_address" to "::", "reflexive_port" to 0, "nat_type" to 0)

The capture had an inert IPv6 because that phone's session was IPv4-only.
The engine reads that block, finds no IPv6 for the peer, and therefore has
no IPv6 channel - and with no IPv6 channel it drops the peer's id and stops
at the punch (section 2). This is the single concrete thing standing
between the current bot and an engine-driven connect.

## 6. The engine's own id and the peer record

- The engine's own id goes in through the engine's own setter
  (`0x7db9730`); the engine turns it into the 16-byte text it stamps on
  every frame (`client+0x8bc`).
- The peer record goes in through the engine's own `SetPeers`
  (`0x7db96b4`), which copies the record field for field and writes the
  peer's id from the record's first dword. The record is 0x218 bytes:

      +0x000  peer id (uint32)
      +0x004  peer IPv4 host block      (0x80)
      +0x084  peer IPv4 reflexive block  (0x80)
      +0x104  peer IPv4 NAT word
      +0x108  peer IPv6 host block      (0x80)
      +0x188  peer IPv6 reflexive block  (0x80)
      +0x208  peer IPv6 NAT word
      +0x20c  the transport's peer id (the engine writes -1 itself)

## 7. Where the evidence lives

    work/qemu/NOTES_engine_p2p_state.md      the lab findings, in order
    work/qemu/byte_compare_verdict.txt       the byte-for-byte verdict
    work/qemu/tools/compare_capture.py       the tool that prints it
    work/qemu/relay_runNN.txt                every datagram the engines sent
    work/qemu/uploadNN.txt                   the guest's own report of a run
