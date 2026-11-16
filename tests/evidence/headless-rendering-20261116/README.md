# Headless rendering investigation, 16 November 2026

An Android 15 controlled match reached live play using **174,990,422 bytes of original extra-resource data in sparse native asset files**, with the original OpenGL ES 3.1 renderer. A later graphics-disabled experiment also reached kickoff with the full extra-resource set. When all 63 extra files were hidden, that bot connected and reached the pre-match introduction, but it did **not** reach kickoff. The released phone APK still requires the full resource set.

## Graphics-disabled and no-extra-file experiments

The recovered 11.1.1 code showed a texture setup call dereferencing an absent graphics resource. A debugger-only guard retained the original behavior for a present resource and skipped its graphics-dimension calls when absent. With the original NullDrv backend and all extra files available, both engines reached live play, each observed 57 ball positions, and the home engine accepted ten Pass presses and releases. No match-state or network-readiness values were fabricated.

A separate test hid all 63 extra resource files and their native aliases before the original JNI startup. Both native peers connected and the server selected a fresh match. With the extra files absent, HOME remained at `Match/Enter/MatchEnterDemoOnline` and had no players after 300 samples; the fully resourced AWAY side reached 22 players. The game's original flow JSON maps `proceed` from that introduction to `Match/MatchIdle`. The subsequent application build started without those 63 files, connected both engines and queued that original event. In 300 samples it still stopped at the introduction with no players; the full-resource peer reached 22 players. The no-download bot did not reach kickoff or accept a Pass. The CI-built app itself installed the graphics-free backend without a debugger, and phone-only preparation reported no resource downloads. This result is from an Android lab, not the user's physical phone.

A later application test ran the original introduction and transition callbacks on GameThread. They completed without a native crash, but HOME still had zero players after 240 observations and did not accept Pass. A read-only comparison showed HOME's pre-match UI task was at state 2, incomplete, with **zero of seven HUD widgets loaded and no HUD-ready event sent**. AWAY, with its extra packs present, loaded all seven HUD widgets, sent its HUD-ready event and reached 22 players. The original game flow waits for this UI task before continuing the match.

Ghidra shows the HUD actor calls `0x2fc3060` to check those presentation widgets, then posts its own UI event if the check succeeds. The identified widgets in the retained original trace include the player radar, attack/defence indicators, goal presentation, virtual pad and match clock. A subsequent headless-only application patch omitted that widget-load check. The app started without all 63 extra packs, both peers connected, and the original HUD event completed the UI task with zero widgets. HOME then reached outer state 12 but still had no players after 240 observations; AWAY reached 22 players. This test did not reach no-download kickoff or accept Pass. Game code shows the next state waits for another original setup condition; its cause remains under investigation.

Another app build allowed the original intro callback to run during either of the two observed pre-match phases. In a 300-sample Android lab test, the callback completed and HOME's original HUD task remained complete without extra files, but HOME still stopped at state 12 with no players. The fully resourced AWAY peer reached state 17 and 22 players. Both peers had the same observed setup-object state; AWAY later received an additional readiness signal that HOME did not. This test did not establish the source of that signal or verify no-download kickoff or Pass.

A later debugger trace identified AWAY's signal as event `0x105001d`, emitted by the original visual-start task at `0x33c880c` and handled by the match at `0x6a4c460`. Read-only observations showed HOME's visual-start flow remaining at state 0 while AWAY's progressed through states 3, 4, 6 and 8, then returned to 0 before emitting the event. Both sides already had the match handler's original ready value. This establishes the event path in the lab; it does not establish that completing the event on HOME will produce a playable match.

In a debugger-assisted probe, HOME queued that original event on GameThread after the above readiness checks. HOME advanced as far as outer state 14, but then crashed in `libUE4.so` at `0x39cf3e8` with a null object read in visual-resource loading; it had no players and did not kick off. The debugger was involved, so the probe alone does not prove the same crash would occur through an application callback. Ghidra shows the failing call also works with visual asset paths and material color values. A headless-only null guard was added to the next app candidate.

The subsequent CI-built app candidate queued the visual-start event itself on GameThread and installed the null guard without a debugger. In a 300-sample Android lab test with all 63 extra packs absent from HOME before preparation, both peers connected, no extra game files were downloaded, and HOME reached outer state 16 with 22 players and a ball. AWAY reached state 17 with 22 players. HOME did not reach the playable state or send Pass; no fatal signal was observed. The next wait is in the original match core at state 52, which checks several conditions including actor readiness. This result does not establish kickoff or a working phone release.

## Reduced-resource native match

The HOME process was stopped at the original `JNI_OnLoad` entry, before RHI initialization and before it had any asset-pack descriptors. Its 63 native aliases were replaced with files of the original logical lengths, containing only the measured ranges. The omitted regions were sparse holes. The renderer-factory call remained unchanged. AWAY retained the complete original resources.

Both owned accounts authenticated again and received a fresh server-selected room, P2P session, matching GameID and accepted DATASYNC phase. A first room request returned `ERR_LIVEDATA_UPDATED`; a fresh normal login cleared it. No server error was overridden.

During 300 samples per side, HOME had 92 playable samples and 44 distinct ball positions; AWAY had 99 playable samples and 47 distinct positions. Both ended at `[16001, 108, 457]`. The HOME automatic input log recorded ten accepted Pass presses and ten accepted releases sent to the private bot engine. The summary's `pass_queue_accepted` field only recognizes the separate manual probe, which was disabled; it must not be interpreted as an automatic-input failure.

The post-JNI read audit includes all captured resource-pack paths, including the project-source directory. It recorded **2,406 `pread64` calls**, all using the sparse native aliases, with **zero reads outside retained ranges and zero unknown-offset reads**. Full source files remained available for Java preparation and as project copies, but no captured native read used those full copies. This establishes a native-engine fixture result, not a phone installer result. Original installed CPKs, the engine library and base APK assets are additional to the seed.

The experiment covers one squad pairing and this observation period. Other squads, stadiums, goals, replays and later screens remain unverified. Production integrity checks still expect full original files, so the seed is **not** a drop-in download for the released APK.

## Reproduce the range seed

`tools/build_match_range_seed.py` checks each source's full size and pinned SHA-256 before copying original ranges. The ZIP contains range data and fingerprints; extraction validates the payloads and reconstructs sparse files at their original offsets. It refuses unsupported names, sizes, hashes, invalid ranges and unexpected ZIP entries. Source binaries and generated ZIPs are not committed.

From the repository root, supply a local source inventory with `files` entries containing `name` and `path`:

```sh
python3 tools/build_match_range_seed.py build \
  --profile tests/evidence/headless-rendering-20261116/full-resource-match-read-ranges.json \
  --sources /path/to/source-inventory.json \
  --output /path/to/range-seed.zip
python3 tools/build_match_range_seed.py extract \
  --archive /path/to/range-seed.zip \
  --output /path/to/new-sparse-directory
```

The generated fixture is **167,265,722 bytes** compressed, retaining 174,990,422 bytes from the original 2,184,159,958 bytes. ZIP SHA-256: `0bf3319aa67c4a264e55c2ce65a9a795822f4e65dec2e1d1cf2a861ea9c99d48`.

Round-trip extraction compared all 174,990,422 retained bytes against their original source files and sampled 441 omitted positions for zero-filled holes. The reconstructed files occupied 175,120,384 allocated bytes while preserving 2,184,159,958 logical bytes. A payload altered with a valid new ZIP CRC was rejected by the range SHA-256 check, and the failed extraction left no output directory.

## What the 175 MB measurement means

Both original engines reached outer state 17 / core state 54, with 22 players and a ball on the pitch. During the 300-second observation, HOME had 101 playable samples and AWAY had 100; each observed 58 distinct ball positions. The production automatic input test was enabled.

Tracing native reads of the additional resource packs, excluding preparation hashes by their source paths, produced 174,990,422 bytes of rounded read ranges for HOME and 174,203,990 for AWAY. The original set still contained 2,184,159,958 bytes. The range profile retains small metadata packs conservatively and rounds reads to 64 KiB. It does not measure the existing installed CPKs, native library or base APK assets.

**This original measurement was an observed subset, not proof that a smaller download worked.** The subsequent reduced-resource native test is described above. General coverage and installation through the phone APK remain unverified.

## Original engine and graphics-disabled tests

The exact 161,937,720-byte library has SHA-256 `2cea0c85344d7d7f295aea1c2353621f02db78dbccd888a0a21a0508ac330659` and MD5 `0b9f845b37e92ec1a77263a5afe3dcaa`. The existing Ghidra project matches that MD5.

The original `-nullrhi` option loads NullDrv. Its initializer stores the original dynamic-RHI object at offset `0xa0111f8` and sets the flag referenced through GOT offset `0x99e4e50`. Runtime reads confirmed that flag was one for HOME and zero for the OpenGL AWAY reference. Native metadata channels connected, but the headless process crashed in the preview transition.

Ghidra and an actual debugger capture located the first invalid resource access at `0x5ff95c8`, called from `0x3a12a24`: its argument was a null parent resource plus `0x60`. A private guard preserved the original valid-resource behavior in 24 instruction-level cases; the next live test instead crashed at `0x3a12bac` with another absent resource. That guard was superseded.

A second private experiment omitted the calls at `0x3a12a24` and `0x3a12de4` only after the original NullDrv flag was confirmed, preserving the queued tasks' cleanup. It still crashed on GameThread during preview. These call omissions are not an accepted fix.

The shared cached nullrhi mode byte at `0x9a95a68`, protected by `0x9a95a70`, also gates original texture-resource construction. For example, `0x5ff12a0` returns before creating and assigning the resource at `param_1[0x13]`. A hardware-watchpoint experiment cleared that cached byte immediately after NullDrv initialization, leaving the original RHI object and NullDrv runtime flag intact. This configuration exited during startup before native channels opened. It also is not an accepted fix.

The missing shader cache was traced to the shader-platform selection. Retaining the original Android profile (feature level 1, shader platform 24, table `[49, 24, 49, 49]`) permitted startup with the original Null driver. A further experiment kept normal client startup and redirected the renderer-factory call to the original Null factory, restoring its actual driver flag after initialization. Startup and native metadata connection succeeded. In 300 observations per side, however, the headless side remained at `Intro/MenuIntroTitleLicense`, the rendered peer waited at `Online/Match/MatchProcessPreMenu`, and neither had players. An instrumentation result of `OK (1 test)` was **not** counted as a successful match.

The original experiments in this section used the unchanged released test APK with lab-only runtime configuration changes. The later application candidates are described above; they have not established a working no-download phone release. No test fabricated network verdicts, match state or simulation state.

## Retained evidence

- `full-resource-match-observation.json`: live-state and ball-motion observations from the full-resource reference.
- `full-resource-match-read-ranges.json`: per-file observed native read ranges and original resource fingerprints.
- `sparse-resource-match-observation.json`: reduced-resource installation, live-state, input and read-coverage observations.
- `range-seed-verification.json`: archive size, exact round-trip comparison, sparse allocation and corruption rejection.
- `null-ui-guard-observation.json`: graphics-disabled and no-extra-file test observations.
- `render-resource-crashes.json`: failed experiment locations and limits.

Private authentication DTOs, account sessions, original game binaries and raw device logs are excluded.
