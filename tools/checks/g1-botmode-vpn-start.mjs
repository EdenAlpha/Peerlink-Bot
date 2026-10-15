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

// (a) the branch exists and is gated on bot mode
must(/import com\.peerlink\.app\.bot\.BotMode/.test(svc), 'a: service imports BotMode');
must(/if \(BotMode\.active\) \{[\s\S]{0,80}startBotModeVpn\(\)/.test(svc), 'a: startVpn branches on BotMode.active into startBotModeVpn');
must(/private fun startBotModeVpn\(\)/.test(svc), 'a: startBotModeVpn() exists');

// (b) the bot start uses the Kotlin engine on the real TUN, no peer machinery
{
  const fn = sliceFn(svc, 'private fun startBotModeVpn(');
  must(fn.length > 200, 'b: startBotModeVpn body found');
  must(/TunnelEngine\(/.test(fn), 'b: constructs TunnelEngine');
  must(/bridgeMode\s*=\s*false/.test(fn), 'b: full datapath (bridgeMode = false)');
  must(!/verifyPeerPath/.test(fn), 'b: no verifyPeerPath in bot start');
  must(!/NativePeerLinkBackend\(/.test(fn), 'b: no NativePeerLinkBackend construction in bot start');
  must(!/resolveForPeer/.test(fn), 'b: no LAN peer resolution in bot start');
  must(!/calculateFabricatedIps/.test(fn), 'b: no calculateFabricatedIps requirement in bot start');
  must(/establish\(\)/.test(fn), 'b: establishes the real TUN interface');
}

// (c) the peer path is intact
must(/Peer LAN IP is not set/.test(svc), 'c: peer-mode peerLanIp guard intact');
must(/resolveForPeer\(this, peerLanIp\)/.test(svc), 'c: peer-mode resolveForPeer intact');
must(/verifyPeerPath\(60_000\)/.test(svc), 'c: peer-mode verifyPeerPath intact');

// (d) stop path tears the bot engine down
{
  const fn = sliceFn(svc, 'private fun stopVpn(');
  must(fn.length > 200, 'd: stopVpn body found');
  must(/botModeEngine/.test(fn), 'd: stopVpn reads botModeEngine');
  must(/botEngine\?\.stop\(\)/.test(fn), 'd: stopVpn stops the bot engine');
}
must(/private var botModeEngine: TunnelEngine\? = null/.test(svc), 'd: botModeEngine field declared');

// (e) BotMode API exists as specified
must(/var active = false/.test(botMode), 'e: BotMode.active flag');
must(/fun ensurePair\(\)/.test(botMode), 'e: ensurePair()');
must(/fun ensureStarted\(ctx: Context, uid: Long\)/.test(botMode), 'e: ensureStarted(ctx, uid)');
must(/fun restoreIfActive\(ctx: Context\)/.test(botMode), 'e: restoreIfActive(ctx)');
must(/@Synchronized[\s\S]{0,60}fun startBeats/.test(botMode), 'e: startBeats is synchronized');

// (f) call sites wired
must(/restoreIfActive\(this\)/.test(pbot), 'f: PeerBotService restores bot mode on create');
must(/restoreIfActive\(this\)/.test(svc), 'f: VPN service restores bot mode on null-intent restart');
must(/ensureStarted\(ctx, identity\.userId\)/.test(roomSvc), 'f: BotRoomService starts bot mode with the minted uid');

// (h) the exact-gameplay-WiFi bind is waived ONLY in bot mode (the 2026-10-15
// engine-death / game-black-hole fix); the peer-mode throw stays.
must(/import com\.peerlink\.app\.bot\.BotMode/.test(tunnel), 'h: engine imports BotMode');
must(/if \(!bindSocketToWifi\(sock\)\) \{[\s\S]{0,400}BotMode\.active/.test(tunnel),
  'h: bindSocketToWifi failure consults BotMode.active');
must(/Exact gameplay network bind failed/.test(tunnel), 'h: peer-mode throw intact');
must(/fun isActive\(\): Boolean = isRunning\.get\(\)/.test(tunnel), 'h: engine exposes isActive()');

// (i) the bot start refuses to claim an active datapath when the engine died
must(/engine\.start\(\)[\s\S]{0,400}if \(!engine\.isActive\(\)\)/.test(svc),
  'i: bot start verifies engine liveness after start');
must(/bot datapath engine failed to start/.test(svc),
  'i: a dead engine tears the VPN start down instead of pretending');

// (g) syntax-level balance on every edited file
for (const [name, text] of [
  ['PeerLinkVpnService.kt', svc],
  ['PeerBotService.kt', pbot],
  ['BotMode.kt', botMode],
  ['BotRoomService.kt', roomSvc],
  ['TunnelEngine.kt', tunnel],
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
