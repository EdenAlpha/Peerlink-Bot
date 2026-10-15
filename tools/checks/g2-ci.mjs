#!/usr/bin/env node
// G2: the pushed commit's CI build succeeds and a release carrying its short
// sha is published. Waits (bounded) while the run is in progress.
import { execSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const REPO = 'EdenAlpha/Peerlink-Bot';
const sleep = (ms) => Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms);

const sh = (cmd) => execSync(cmd, { cwd: root, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim();

let sha;
try {
  sha = sh('git rev-parse --short=7 HEAD');
} catch (e) {
  console.log('G2 FAILED: git rev-parse failed: ' + e.message);
  process.exit(1);
}

// Find the CI run for this exact commit.
let runId = null;
try {
  const runs = JSON.parse(sh(`gh run list -R ${REPO} -L 15 --json databaseId,headSha,status,conclusion`));
  const mine = runs.find((r) => (r.headSha || '').startsWith(sha));
  if (mine) runId = mine.databaseId;
} catch (e) {
  console.log('G2 FAILED: gh run list failed: ' + e.message);
  process.exit(1);
}
if (!runId) {
  console.log(`G2 WAITING: no CI run exists yet for ${sha} (push first, then --reverify)`);
  process.exit(1);
}

// Wait for the run to conclude (bounded ~13 minutes).
const deadline = Date.now() + 13 * 60_000;
let conclusion = '';
while (Date.now() < deadline) {
  const j = JSON.parse(sh(`gh run view ${runId} -R ${REPO} --json status,conclusion`));
  if (j.status === 'completed') { conclusion = j.conclusion; break; }
  sleep(30_000);
}
if (conclusion !== 'success') {
  console.log(`G2 FAILED: run ${runId} for ${sha} concluded '${conclusion || 'timeout'}'`);
  process.exit(1);
}

// A release must carry this short sha.
const releases = JSON.parse(sh(`gh release list -R ${REPO} -L 20 --json tagName,name`));
const hit = releases.find((r) => (r.tagName || '').includes(sha));
if (!hit) {
  console.log(`G2 FAILED: no release tag contains ${sha}`);
  process.exit(1);
}

console.log(`G2 OK (run ${runId} success; release ${hit.tagName})`);
