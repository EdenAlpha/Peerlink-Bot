# Gates: Bot-mode VPN start (the "Peer LAN IP is not set" fix)

OWNS: app/src/main/java/com/peerlink/app/service/PeerLinkVpnService.kt, app/src/main/java/com/peerlink/app/service/PeerBotService.kt, app/src/main/java/com/peerlink/app/bot/BotMode.kt, app/src/main/java/com/peerlink/app/bot/BotRoomService.kt, app/src/main/java/com/peerlink/app/bot/BotMatchDriver.kt, app/src/main/java/com/peerlink/app/bot/PeerSlot.kt, app/src/main/java/com/peerlink/app/tunnel/TunnelEngine.kt, app/src/main/java/com/peerlink/app/network/LanPathResolver.kt, docs/GATE_FLOW.md, tools/checks/**

Scope: In bot mode the VPN starts with no peer device (the Kotlin engine owns the real TUN; no LAN-path lock, no peer probe, no native peer backend) and peer mode stays intact; the fix is shipped through CI, with on-device acceptance recorded as manual gates.

- [x] G0: this ledger states outcomes that can fail
  CHECK: "C:/Users/Administrator/tools/node/node-v22.14.0-win-x64/node.exe" "C:/Users/Administrator/.config/opencode/skills/unlazy/scripts/gate-lint.mjs" GATES.md
  EXPECT: LINT OK
  EVIDENCE: automatic-evidence=v1; definition-sha256=8feaf7373537100e93f03b54c6089053e184ba69f529576b9f0696eb637ffa5e; exit=0; EXPECT=matched; output-sha256=cdf4f1ded3ae9e7cb71dc992ac78ac082f54f67a522458fd1066a7b3460bfbc3; output-bytes=651; shell=C:\Windows\system32\cmd.exe; cwd=C:\Users\Administrator\Documents\Default Project\Peerlink-app; path=f2773bea5a0d/18 entries

- [x] G1: the bot-mode start path is implemented as specified (the SAME native datapath as peer mode, with the native "peer" pointed at the loopback PeerSlot; default underlay; no peer probe; the dead Kotlin-datapath attempt fully removed) and the peer path is intact
  CHECK: "C:/Users/Administrator/tools/node/node-v22.14.0-win-x64/node.exe" tools/checks/g1-botmode-vpn-start.mjs
  EXPECT: G1 OK
  EVIDENCE: automatic-evidence=v1; definition-sha256=5304a70f53e59aeee54294ba3c7e304d304a0a69a931bc9e35344ca4a1cd1b33; exit=0; EXPECT=matched; output-sha256=4154f5155ffc16a37ec48872f3b15bbdc0e1f36595439e1af0172c1ed8fe47df; output-bytes=22; shell=C:\Windows\system32\cmd.exe; cwd=C:\Users\Administrator\Documents\Default Project\Peerlink-app; path=f2773bea5a0d/18 entries

- [x] G2: the pushed commit's CI build succeeds and a release carrying its short sha is published
  CHECK: "C:/Users/Administrator/tools/node/node-v22.14.0-win-x64/node.exe" tools/checks/g2-ci.mjs
  EXPECT: G2 OK
  EVIDENCE: automatic-evidence=v1; definition-sha256=d0ed9d57fc4ee5e530aa43487de10987470ead7b0d4cb2c507f35a7f9effc75f; exit=0; EXPECT=matched; output-sha256=1bca386b6b40d5ff2fa9ea6ef36f54a2d05e8506168463a25b0e816201b431fe; output-bytes=58; shell=C:\Windows\system32\cmd.exe; cwd=C:\Users\Administrator\Documents\Default Project\Peerlink-app; path=f2773bea5a0d/18 entries

- [ ] G3: on-device -- after installing the latest build and approving the one-time VPN dialog, Create room produces a running VPN (status-bar key plus the bot-mode start lines in the app log) and no "Peer LAN IP is not set" error
  EVIDENCE: pending

- [ ] G4: on-device -- the game joins the room and the bot notification marches to CONNECTED, or the last notification line it reached is recorded
  EVIDENCE: pending

- [ ] G5: on-device -- with the bot VPN running, the real game still reaches Konami and logs in normally (the engine never leaves the game's traffic black-holed; the 2026-10-15 login regression)
  EVIDENCE: pending
