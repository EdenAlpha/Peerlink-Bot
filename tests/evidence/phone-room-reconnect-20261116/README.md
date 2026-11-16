# Room restart with a running VPN

The phone export `peerlink_match_1794840438003.zip` recorded the VPN destination
as the first room's listener, while later rooms reserved different ports. Both
subsequent punch-loop probes failed. The direct route independently rejected
valid game packets with `unexpected_game_source`, before recording game RX.

Commit `01e32e5` introduced per-room port reservations without updating the
running native VPN destination on replacement. Commit `cec1767` restricted
direct sources to loopback or the bot's declared address, omitting the game's
declared address and other addresses owned by the phone. These are concrete
routing regressions; they do not establish every cause of the phone's native
network-I/O timeout.

Production fix: `805ab1b`; build-identity configuration: `4a86b1e`.
The packaged source is `4a86b1e03790a4e7f3c58b17ea3e6a706cded5b0`.
The released APK is the unchanged artifact from [CI 43893442295](https://github.com/EdenAlpha/Peerlink-Bot/actions/runs/43893442295),
artifact ID `14238501355`, 36,146,715 bytes, SHA-256
`a5229fd91c910b0aa59079ba1684026c2330d4cec806a80d1c951e10f7d84882`.

## Checks

- `BotFerryTransportTest`: all three tests passed on ARM64 Android 15. The
  native VPN retained its running backend across two listener replacements;
  game data and replies traversed it unchanged. The tests also exercise another
  local IPv4 source, separate host/reflexive return paths, and counters.
- Test-only revision `0c5769e` corrects fixture ordering and uses the reflexive
  candidate for the native-TUN assertion. Only its instrumentation APK was
  installed; the main APK hash was checked before and after that installation.
- The full match passed through `PeerSlot` and `NativePeerLinkBackend` after
  moving from port 54225 to 44528 and verifying the new room's probe. Both
  original engines reached kickoff, with 22 players each, in 35.082 and 53.242
  seconds. Both completed 180 samples with matching final ball coordinates.
- Only the home bot received a Pass input. Its observed peak ball speed was
  18.719 m/s; the reference received no controller input. No debugger was
  attached, and no native failure or crash was recorded.
- The bot's 63 extra resource packs were absent; its resource preparation
  performed zero extra downloads. The rendered reference had the full packs.
- App unit tests, native policy checks and Android 14/15 host checks passed.

`match-proof.json` contains the sanitized retained-match verification, including
the private archive's integrity hash. `routing-tests.txt` contains the test
runner's result. No game binaries, credentials, session IDs or packets are
included.

## Limits

The lab replaces the TUN device with an AF_UNIX datagram socket pair while using
the production native backend. It configures an owned local TLS TURN fixture.
It does not reproduce the Infinix's mobile network, actual Konami TURN service,
Android VPN service lifecycle, or memory pressure from the user's foreground
game. The phone export's network-I/O timeout may have an additional cause;
kickoff against the physical phone remains unconfirmed.
