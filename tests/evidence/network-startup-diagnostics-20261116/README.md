# Phone network-startup investigation

The physical-phone failure is still unresolved. The latest uploaded export,
`peerlink_match_1794845885673.zip`, identifies source `4a86b1e` (5.0.15).
The VPN probe succeeds and both game candidate routes receive packets, but
the original SDK never publishes ready network-I/O objects. No bot peer socket
is learned. The export ends before the connection deadline.

## Comparison with the earlier connected phone run

Export `1794822383161` recorded 2,761 game packets and 2,756 bot packets,
accepted match setup, and a later pre-match failure. Its release chronology
matches 5.0.13 / `825a50a`; the older export has no embedded source identifier,
so the APK attribution is not independently established by the ZIP.

The complete files for BotMatchDriver, BotMatchFlow, EngineHost, EngineRuntime,
GameAndroidRuntime, PeerSlot, BotMode, BotRoomService, PeerBotService,
PeerLinkVpnService, NativePeerLinkBackend, LanPathResolver and
`peerlink_backend.cpp` are identical between `825a50a` and the first failing
5.0.14 / `f6ccb56`. Match-preview changes and a startup graphics guard changed
in that interval. The later 5.0.15 routing repairs do change PeerSlot,
PeerLinkVpnService, NativePeerLinkBackend and `peerlink_backend.cpp`.

Identical source in selected connection files does not exclude an indirect
regression elsewhere, nor does it prove a particular network configuration is
the cause. No rollback has yet been shown to repair the physical-phone failure.

## Additional controlled comparison

The exact released `4a86b1e` APK also connected both native engines with:

- The original real-server TURN inputs rather than only the owned TURN fixture.
- A kernel IPv4/IPv6 TUN interface present.
- A 130-second pause after TURN configuration.
- The original non-Wi-Fi/Ethernet port allocator selected before SDK Start.

The last comparison changed only the SDK's cached allow-Ethernet setting in
the disposable lab processes. Both original engines reported Wi-Fi=0,
Ethernet=0 and valid IPv4 network-I/O, and synchronized their channels. It did
not emulate a cellular modem. None of these checks reproduces the complete
physical game/Android per-app VPN path.

## Diagnostic change

Source `e408d9d` adds original SDK startup phase/error, thread-stop state,
network-type flags, and network-object presence to `MATCH_LOADING` diagnostics.
It also observes the original platform address resolver at `7dfc590`, reached
through `7dc25a0 -> 7dc9c84 -> platform vtable+d0` in the pinned 11.1.1 library.

The observer forwards all six arguments, returns the original result, and
preserves the incoming/outgoing `errno`. It records each family's call count,
pending duration and result; it does not log hostnames, credentials or payloads.
If installation is unsupported, normal engine startup continues. It neither
substitutes addresses nor changes SDK startup arguments or connection verdicts.

`dnsV4`/`dnsV6` distinguish not-called, pending, resolved and failed lookups.
`0x0e000001` is the original resolver's success result. A failed IPv6 lookup
alone need not be fatal if IPv4 succeeds. Numeric SDK/error results are retained
instead of assigning an unproven cause to all zero-I/O outcomes.

Source `3b8ddb5` exports these reports immediately after preparation and every
five seconds during introduction, without waiting for match setup. An Android
instrumentation assertion verifies that both roles receive the report in the
same `AppState` log used by the export, before any match is created. This matters
because the older match-loading monitor only starts after `SETUP_MATCH`.

Source `5c1a06b` additionally observes the pinned library's actual imports for
`socket`, `bind`, `connect`, `getsockname`, `getaddrinfo`, `sendto` and `sendmsg`.
It retains call/failure counts and original errno/EAI values. Send reports show
the latest IPv4 and loopback destinations, total loopback attempts and sends
accepted by the kernel. Acceptance is not proof of delivery to the other
engine. Payload bytes are neither inspected nor logged. These observers are
limited to this library's imports and preserve arguments, results and errno.
`socketObservers=127` means all seven observers installed; otherwise coverage
is incomplete and cannot be used to rule out unseen operations.

This directly addresses an important limitation of the previous exports:
absence of a socket learned by PeerSlot does not prove the engine never tried
to create a socket or send elsewhere. Routing remains a possible cause until
the physical phone's actual errors and destinations are observed.

## Verification

- Both native roles connected with the released diagnostic APK and real-server
  TURN inputs. Both passed the new assertion that startup details reached the
  export before match setup.
- A separate, disposable lab test replaced the original STUN hostname input
  with a `.invalid` name. The original SDK itself entered phase 7 with error
  field 0 and absent network-I/O objects. The resolver observer independently
  captured its original `0x0e000003` lookup failures. No connection flags,
  socket pointers, or return values were fabricated. This confirms why the
  old I/O=0/error=0 fields could conceal a real failure; it does not establish
  DNS as the physical phone's cause.
- Android 14/15 host checks, unit tests and native policy tests passed. The
  Android 14 job was rerun after Maven Central returned HTTP 429 while fetching
  a dependency. The successful APK build was retained unchanged.
- `network_socket_diagnostics_test.cpp` exercises the observers with real
  datagram sockets. It verifies unchanged received bytes, the actual destination,
  success errno preservation, bind/connect/send/name failures and a numeric-only
  DNS failure. This runs locally without any external DNS/network request.

## Saved kickoff versus normal app entry

The exact successful `4a86b1e` APK, its hash, kickoff/Pass observations and test
limits remain in [the retained kickoff evidence](../phone-room-reconnect-20261116/README.md).
The native startup APIs are called by the complete APK in both paths:
preparation, revision configuration, transport-account request/configuration,
peer record, introduction, network-I/O wait, session, channels and match setup.

There are concrete execution differences that a matching APK hash does not
remove. The harness constructs its local packet-routing fixture before engine
preparation; `BotRoomService` prepares the engine, creates the room, then starts
the actual Android VPN. The harness uses a socket-pair TUN and another hosted
engine, while the normal app uses Android's per-app VPN and the installed game.
The lab's network, foreground/background transitions and resolver behavior are
also different. Both the older connected and newer failed phone exports show
the engine already loaded when the real VPN starts, so that order difference
alone is not an established regression. No missing pre-introduction engine call
has been demonstrated by this comparison.

This is a diagnostic change, not a confirmed repair of the physical phone.
