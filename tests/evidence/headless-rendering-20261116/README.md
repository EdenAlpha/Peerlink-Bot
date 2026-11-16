# Headless rendering investigation, 16 November 2026

The Android 15 reference match reached live play with the **full resource set and OpenGL ES 3.1 rendering**. The graphics-disabled experiments have **not** reached kickoff. No smaller resource release has been validated or published.

## What the 175 MB measurement means

Both original engines reached outer state 17 / core state 54, with 22 players and a ball on the pitch. During the 300-second observation, HOME had 101 playable samples and AWAY had 100; each observed 58 distinct ball positions. The production automatic input test was enabled.

Tracing native reads of the additional resource packs, excluding preparation hashes by their source paths, produced 174,990,422 bytes of rounded read ranges for HOME and 174,203,990 for AWAY. The original set still contained 2,184,159,958 bytes. The range profile retains small metadata packs conservatively and rounds reads to 64 KiB. It does not measure the existing installed CPKs, native library or base APK assets.

**This is an observed subset for one match, not a 175 MB downloadable package.** A candidate must pass a cold start and actual live-match test with the omitted ranges absent. Coverage of other player rosters, match events and later screens is also unverified. The released APK still validates and requires the complete pinned resource set.

## Original engine and graphics-disabled tests

The exact 161,937,720-byte library has SHA-256 `2cea0c85344d7d7f295aea1c2353621f02db78dbccd888a0a21a0508ac330659` and MD5 `0b9f845b37e92ec1a77263a5afe3dcaa`. The existing Ghidra project matches that MD5.

The original `-nullrhi` option loads NullDrv. Its initializer stores the original dynamic-RHI object at offset `0xa0111f8` and sets the flag referenced through GOT offset `0x99e4e50`. Runtime reads confirmed that flag was one for HOME and zero for the OpenGL AWAY reference. Native metadata channels connected, but the headless process crashed in the preview transition.

Ghidra and an actual debugger capture located the first invalid resource access at `0x5ff95c8`, called from `0x3a12a24`: its argument was a null parent resource plus `0x60`. A private guard preserved the original valid-resource behavior in 24 instruction-level cases; the next live test instead crashed at `0x3a12bac` with another absent resource. That guard was superseded.

A second private experiment omitted the calls at `0x3a12a24` and `0x3a12de4` only after the original NullDrv flag was confirmed, preserving the queued tasks' cleanup. It still crashed on GameThread during preview. These call omissions are not an accepted fix.

The shared cached nullrhi mode byte at `0x9a95a68`, protected by `0x9a95a70`, also gates original texture-resource construction. For example, `0x5ff12a0` returns before creating and assigning the resource at `param_1[0x13]`. A hardware-watchpoint experiment cleared that cached byte immediately after NullDrv initialization, leaving the original RHI object and NullDrv runtime flag intact. This configuration exited during startup before native channels opened. It also is not an accepted fix.

All experiments used the unchanged released test APK with lab-only runtime configuration changes. Production application code, resource integrity checks, network verdicts, match state and simulation state were not changed. The speculative runtime changes have not been added to a phone release.

## Retained evidence

- `full-resource-match-observation.json`: live-state and ball-motion observations from the full-resource reference.
- `full-resource-match-read-ranges.json`: per-file observed native read ranges and original resource fingerprints.
- `render-resource-crashes.json`: failed experiment locations and limits.

Private authentication DTOs, account sessions, original game binaries and raw device logs are excluded.
