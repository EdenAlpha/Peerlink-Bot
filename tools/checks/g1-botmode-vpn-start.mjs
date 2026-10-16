#!/usr/bin/env node
// G1: the bot-mode VPN start is implemented as specified and the peer path is
// intact. Reads the actual repository files; prints a success-only marker.
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const read = (p) => readFileSync(join(root, p), 'utf8');

// ---- matcher self-control: absence detection must be able to fail ----
{
  const s = 'alpha\nbeta';
  if (!s.includes('beta')) { console.log('G1 CONTROL BROKEN (positive)'); process.exit(1); }
  if (s.includes('gamma')) { console.log('G1 CONTROL BROKEN (negative)'); process.exit(1); }
}

const failures = [];
const passes = [];
const must = (cond, what) => (cond ? passes : failures).push(what);

function sliceFn(text, name) {
  const i = text.indexOf(name);
  if (i < 0) return '';
  const j = text.indexOf('\n    private fun ', i + 1);
  return text.slice(i, j > 0 ? j : text.length);
}

const svc = read('app/src/main/java/com/peerlink/app/service/PeerLinkVpnService.kt');
const botMode = read('app/src/main/java/com/peerlink/app/bot/BotMode.kt');
const roomSvc = read('app/src/main/java/com/peerlink/app/bot/BotRoomService.kt');
const pbot = read('app/src/main/java/com/peerlink/app/service/PeerBotService.kt');
const tunnel = read('app/src/main/java/com/peerlink/app/tunnel/TunnelEngine.kt');
const peerSlot = read('app/src/main/java/com/peerlink/app/bot/PeerSlot.kt');
const lanRes = read('app/src/main/java/com/peerlink/app/network/LanPathResolver.kt');
const driver = read('app/src/main/java/com/peerlink/app/bot/BotMatchDriver.kt');
const gate = read('app/src/main/java/com/peerlink/app/bot/KgsGate.kt');
const quality = read('app/src/main/java/com/peerlink/app/bot/BotRelayQuality.kt');
const squad = read('app/src/main/java/com/peerlink/app/bot/BotSquad.kt');
const captures = read('app/src/main/java/com/peerlink/app/bot/GateCaptures.kt');
const minter = read('app/src/main/java/com/peerlink/app/bot/BotMinter.kt');

// (a) the branch exists and is gated on bot mode
must(/import com\.peerlink\.app\.bot\.BotMode/.test(svc), 'a: service imports BotMode');
must(/if \(BotMode\.active\) \{[\s\S]{0,80}startBotModeVpn\(\)/.test(svc), 'a: startVpn branches on BotMode.active into startBotModeVpn');
must(/private fun startBotModeVpn\(\)/.test(svc), 'a: startBotModeVpn() exists');

// (b) the bot start uses the NATIVE datapath with a loopback peer
{
  const fn = sliceFn(svc, 'private fun startBotModeVpn(');
  must(fn.length > 400, 'b: startBotModeVpn body found');
  must(/NativePeerLinkBackend\(/.test(fn), 'b: constructs the native backend (the peer-mode datapath)');
  must(/peerLanIp = PeerSlot\.LOOPBACK_PEER_IP/.test(fn), 'b: the native peer is the loopback slot');
  must(/peerPort = BotMode\.BOT_PORT/.test(fn), 'b: peer port is the slot port');
  must(/LanPathResolver\.resolveDefaultUnderlay\(this\)/.test(fn), 'b: default underlay (Wi-Fi or mobile)');
  must(/PassthroughBridgeEngine\(/.test(fn), 'b: passthrough bridge starts as in peer mode');
  must(/fabricateStunWithExistingLogic/.test(fn), 'b: STUN fabrication wired as in peer mode');
  must(/detachFd\(\)/.test(fn), 'b: TUN detached to the native backend');
  must(!/TunnelEngine\(/.test(fn), 'b: no Kotlin engine in the bot datapath');
  must(!/verifyPeerPath/.test(fn), 'b: no peer-link probe in the bot start');
  must(!/resolveForPeer/.test(fn), 'b: no LAN peer resolution in the bot start');
}

// (c) the peer path is intact
must(/Peer LAN IP is not set/.test(svc), 'c: peer-mode peerLanIp guard intact');
must(/resolveForPeer\(this, peerLanIp\)/.test(svc), 'c: peer-mode resolveForPeer intact');
must(/verifyPeerPath\(60_000\)/.test(svc), 'c: peer-mode verifyPeerPath intact');

// (d) the dead Kotlin-engine path is gone; teardown covers the native stack
must(!/botModeEngine/.test(svc), 'd: dead Kotlin datapath field fully removed');
{
  const fn = sliceFn(svc, 'private fun stopVpn(');
  must(fn.length > 200, 'd: stopVpn body found');
  must(/passthroughBridgeEngine/.test(fn), 'd: stopVpn tears the bridge down');
  must(/backend\?\.requestStop\(\)/.test(fn), 'd: stopVpn tears the native backend down');
}

// (e) BotMode API exists as specified
must(/var active = false/.test(botMode), 'e: BotMode.active flag');
must(/fun ensurePair\(\)/.test(botMode), 'e: ensurePair()');
must(/fun ensureStarted\(ctx: Context, uid: Long\)/.test(botMode), 'e: ensureStarted(ctx, uid)');
must(/fun restoreIfActive\(ctx: Context\)/.test(botMode), 'e: restoreIfActive(ctx)');
must(/@Synchronized[\s\S]{0,60}fun startBeats/.test(botMode), 'e: startBeats is synchronized');

// (f) call sites wired
must(/restoreIfActive\(this\)/.test(pbot), 'f: PeerBotService restores bot mode on create');
must(/restoreIfActive\(this\)/.test(svc), 'f: VPN service restores bot mode on null-intent restart');
must(/ensureStarted\(ctx, identityInUse\.userId\)/.test(roomSvc) ||
     /ensureStarted\(ctx, identity\.userId\)/.test(roomSvc),
  'f: BotRoomService starts bot mode with the minted uid');

// (h) the peer slot speaks the native wire format on loopback
must(/LOOPBACK_PEER_IP = "127\.0\.0\.1"/.test(peerSlot), 'h: slot is the loopback peer');
must(/BACKEND_PORT = 17024/.test(peerSlot), 'h: backend peer-socket port known');
must(/0xFC/.test(peerSlot), 'h: peer probe 0xFC handled');
must(/0xFDu/.test(peerSlot), 'h: probe reply 0xFD sent');
must(/DIAG_HEADER_SIZE = 56/.test(peerSlot), 'h: native diag header stripped');
must(/buildInnerUdpPacket\(/.test(peerSlot), 'h: replies are complete inner IP packets');
must(/parseInnerUdp\(/.test(peerSlot), 'h: inner IPv4/UDP frames parsed');
must(/fun resolveDefaultUnderlay\(context: Context\): LanPath\?/.test(lanRes),
  'h: default-underlay resolver exists');
// (h2) bot mode never pins/binds the peer socket to a physical interface:
// the peer is LOOPBACK (127.0.0.1:1162) and a pinned/Network-bound socket
// cannot route there (Tunneled=0 across the 04:37 cellular and 05:09 runs,
// 2026-10-16; IP_UNICAST_IF + Network.bindSocket both break loopback).
must(/localInterfaceIndex = 0,/.test(svc),
  'h2: bot mode does not pin the peer socket to a physical interface (index 0)');
must(/localLanIp = "127\.0\.0\.1",/.test(svc),
  'h2: bot mode binds the peer socket source to loopback (WAN source cannot deliver to loopback)');
must(/InetSocketAddress\(LOOPBACK_PEER_IP, BACKEND_PORT\)/.test(peerSlot),
  'h2: the slot sends to the backend loopback listener');
must(/rx datagram #/.test(peerSlot),
  'h2: every slot datagram is counted and logged (the hop stays visible)');
{
  const prep = svc.indexOf('private fun prepareNativePeerSocket');
  const prepEnd = svc.indexOf('private fun isUsableUnderlay', prep);
  const seg = svc.slice(prep, prepEnd);
  must(seg.indexOf('BotMode.active') >= 0 &&
      seg.indexOf('activeNetwork?.bindSocket') < 0 &&
      seg.indexOf('protect(') >= 0,
    'h2: bot-mode peer socket prep is protect-only (no Network bind)');
}
must(/\[NATIVE-STAT\] tunnelOut=/.test(svc) && /kaTx=/.test(svc),
  'h2: the native peer-path counters are visible in the log');
must(/nativeBackend!!.startPolling\(\)/.test(svc),
  'h2: bot mode starts the native stats polling (NATIVE-STAT would never fire)');
must(/bindSocketFdToPreferredNetwork/.test(svc), 'h: peer-mode socket bind intact');

// (i) the exact-gameplay-WiFi waiver stays bot-mode-only in the Kotlin engine
// (defense in depth: the engine is no longer the bot datapath, but the waiver
// must never leak into peer mode).
must(/import com\.peerlink\.app\.bot\.BotMode/.test(tunnel), 'i: engine imports BotMode');
must(/if \(!bindSocketToWifi\(sock\)\) \{[\s\S]{0,400}BotMode\.active/.test(tunnel),
  'i: bindSocketToWifi failure consults BotMode.active');
must(/Exact gameplay network bind failed/.test(tunnel), 'i: peer-mode throw intact');
must(/fun isActive\(\): Boolean = isRunning\.get\(\)/.test(tunnel), 'i: engine exposes isActive()');

// (j) the driver never stops silently and never gives up early (v2)
must(!/stopping/.test(driver), 'j: no rung gives up with a "-- stopping" anymore');
must(/READY_REASSERT_MS/.test(driver), 'j: the ready re-assert cadence exists');
must(/fun transient\(/.test(driver) && /_NET_FAIL/.test(driver),
  'j: transport noise is retried, not fatal');
must(/fun already\(/.test(driver) && /startsWith\("ERR_ALREADY"\)/.test(driver),
  'j: ERR_ALREADY_* answers count as the done state');
must(/PeerSlot\.arm\(opp, gate\.uid, "", "", 0\)/.test(driver),
  'j: the peer slot arms on go_match (reactive before the ladder ends)');
must(/PeerSlot\.arm\(opp, gate\.uid, peerAddr, localIp, localPort\)/.test(driver),
  'j: the slot is re-targeted with the session address');
must(!/SEAT_TIMEOUT_MS|GO_TIMEOUT_MS/.test(driver),
  'j: the v1 seat/go deadlines are gone (no silent give-up)');
{
  const seatFn = sliceFn(driver, 'private fun waitForSeat(');
  must(seatFn.length > 100 && !/deadline/i.test(seatFn),
    'j: the seat watch runs with no deadline');
}
must((driver.match(/\bstatus\("/g) || []).length >= 6,
  'j: every rung narrates its state to the notification');
must(/if \(armed && gameUid == oppUid && socket != null\)/.test(peerSlot),
  'j: PeerSlot.arm is idempotent (early arm + re-target)');
must(/PeerBotEngine\(botUid\.toString\(\)\)/.test(peerSlot),
  'n: the 0x9090 stamp carries the BOT uid (stamping the game uid made every reply self-contradictory)');

// (k) the bot declares a TRANSLATED endpoint at the gate (2026-10-15 device
// run: host .53.2 -- an address that exists nowhere on the wire -- got
// ERR_EMPTY x7 (empty endpoint record); run #21's host == reflexive == real
// egress was accepted, and the real games in flipped peer rooms declare real
// hosts with fabricated reflexives: the host must be owned, the reflexive is
// the punched address).
must(/var hostIp/.test(botMode), 'k: BotMode captures the declare-host IP');
must(/resolveDefaultUnderlay\(ctx\)\?\.localIp/.test(botMode),
  'k: the host is the phone\u2019s real LAN IP');
must(/hostAddress = hostIp/.test(botMode),
  'k: the declared host is the real LAN IP, not the fabricated one');
must(/reflexiveAddress = BOT_FAB_IP/.test(botMode),
  'k: the reflexive stays the fabricated peer identity');
must(!/fun fabricated\(/.test(gate),
  'k: the host==reflexive helper is gone');

// (l) the relay-quality ladder is wired in the proven places (2026-10-15
// night: BOTH endpoint shapes were refused ERR_EMPTY x7 -- the real
// discriminator was the never-sent quality report; every accepted session
// on record SETs it at login AND in the room before CME -> ready).
must(/fun fetchRows\(/.test(quality) && /CMD_GET_GAMERELAY_QUALITYCHECK_LIST/.test(quality),
  'l: the qualitycheck list is fetched from the gate');
must(/PINGCHAIN3QUALITYCHECK000000000000/.test(quality),
  'l: the UDP echo probe is the VM-proven payload');
must(/CALLABLE|Callable\(/.test(quality) || /newFixedThreadPool/.test(quality),
  'l: regions are measured concurrently');
must(/setRelayQuality\(/.test(quality), 'l: the measured rows are SET to the gate');
must(/fun loginReport\(/.test(quality), 'l: the login-time report exists');
must(/fun roomReport\(/.test(quality), 'l: the room-time report exists');
must(/BotRelayQuality\.loginReport\(gate\)/.test(roomSvc),
  'l: BotRoomService runs the login-time report');
{
  const es = driver.indexOf('entry_side -> NOERR');
  const qual = driver.indexOf('BotRelayQuality.roomReport(gate)');
  const cme = driver.indexOf('check_match_enable, immediately before');
  must(es >= 0 && qual > es && cme > qual,
    'l: the room-time report sits between entry_side and check_match_enable (run #47 order)');
}
must(!/qualityTemplate/.test(roomSvc), 'l: the dead qualityTemplate path is gone');

// (m) the squad bootstrap (T5 live proof, 2026-10-15: a minted account has
// no squad -> GET_USER_SQUAD ERR_NOT_ENOUGH_PLAYER_IN_SQUAD -> its ready is
// refused ERR_EMPTY/PNPS-; SET_MYCLUB_ENTRY_INFO from the mint's own
// entry_info fixes it -- ready NOERR, go flipped YES on the live gate).
must(/SET_MYCLUB_ENTRY_INFO_BODY_HEX/.test(captures),
  'm: the captured SET_MYCLUB_ENTRY_INFO body is committed');
must(/fun check\(|fun setFromEntryInfo\(|fun ensure\(/.test(squad),
  'm: BotSquad check/bootstrap/verify ladder exists');
must(/ERR_NOT_ENOUGH_PLAYER_IN_SQUAD/.test(squad),
  'm: the squad-less gate answer is handled');
must(/squad_gameplan/.test(squad) && /squadData\["player_list"\] = players/.test(squad),
  'm: the SET keeps the template squad_gameplan (dropping it = ERR_INVALIDARG)');
must(/BotSquad\.setFromEntryInfo\(checkGate, entryInfo\)/.test(minter),
  'm: mint bootstraps the squad on its verify session');
must(/entryInfoJson = if \(entryInfo\.isEmpty\(\)\) ""/.test(minter) ||
     /BotSquad\.mapToJson\(entryInfo\)/.test(minter),
  'm: mint stores the roster for later bootstrap');
must(/BotSquad\.ensure\(gate, identity\.entryInfoJson\)/.test(roomSvc),
  'm: create-room guards the squad before anything else');
must(/bot account has no usable squad -- minting a replacement/.test(roomSvc),
  'm: a squad-less old account is replaced automatically');

// (n) the peer socket re-bind (2026-10-16 04:37 run: the game's 14 punches
// were diverted but never forwarded to the slot -- Tunneled=0; the backend
// connected to the loopback slot at VPN start, before the slot existed, and
// the first keepalive poisoned the connected UDP socket).
must(/var onArmed/.test(peerSlot) && /onArmed\?\.invoke\(\)/.test(peerSlot),
  'n: the slot exposes the bind hook');
must(/PeerSlot\.onArmed = \{ verifyBotPunchLoop\(\) \}/.test(svc) &&
     /private fun verifyBotPunchLoop\(\)/.test(svc) &&
     /verifyPeerPath\(3_000\)/.test(svc) && /rebindPeerSocket\(\)/.test(svc),
  'n: the arm hook re-binds and verifies the punch loop');

// (g) syntax-level balance on every edited file
for (const [name, text] of [
  ['PeerLinkVpnService.kt', svc],
  ['PeerBotService.kt', pbot],
  ['BotMode.kt', botMode],
  ['BotRoomService.kt', roomSvc],
  ['TunnelEngine.kt', tunnel],
  ['PeerSlot.kt', peerSlot],
  ['LanPathResolver.kt', lanRes],
  ['BotMatchDriver.kt', driver],
  ['KgsGate.kt', gate],
  ['BotRelayQuality.kt', quality],
  ['BotSquad.kt', squad],
  ['GateCaptures.kt', captures],
  ['BotMinter.kt', minter],
]) {
  const ob = (text.match(/\{/g) || []).length, cb = (text.match(/\}/g) || []).length;
  const op = (text.match(/\(/g) || []).length, cp = (text.match(/\)/g) || []).length;
  must(ob === cb, `g: ${name} braces ${ob}/${cb} balanced`);
  must(op === cp, `g: ${name} parens ${op}/${cp} balanced`);
}

if (failures.length) {
  console.log('G1 FAILED:');
  for (const f of failures) console.log('  - ' + f);
  process.exit(1);
}
console.log(`G1 OK (${passes.length} assertions)`);
