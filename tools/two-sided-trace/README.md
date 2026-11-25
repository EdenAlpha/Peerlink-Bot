# Two-sided celebration investigation

These are **experimental lab tools** for comparing the original eFootball
11.1.1 engine with PeerLink's bot in the same match. They do not change the
PeerLink APK. Initial live lab matches produced 194 and 379 verified full-game
checks whose checksums and 71 contributors matched the bot through the first
half. Both ended during the halftime transition.

`fullgame-observer.js` uses Frida to record the game's own checksum, its 71
contributors, selected ball/player values, and scene-start flags. It verifies
each reconstructed checksum against the engine before marking it usable.
`run-fullgame-observer.py` saves numeric events continuously as JSON lines.
`compare-match-checks.py` compares verified full-game checks with the bot's
`MATCH_CHECK_FRAME` log entries. A differing contributor helps locate a
disagreement; it does not by itself prove the root cause.

`TouchInjector.java` is a lab-only Android shell utility that injects a held
touch and a second action touch. Android accepted repeated test injections.
`play-lab-game.py` uses verified observations and a fresh screenshot to control
the full-game side. The raw reported ball carrier remained stale during one live
match, so the controller also uses screen controls and proximity. Later live
matches exercised the controller and produced different scenes; it cannot
reliably trigger a particular scene. It is not PeerLink's bot logic. The
screenshot-derived button templates and screenshots are not included here.

The native offsets apply **only** to the original 11.1.1 `libUE4.so` with
SHA-256 `2cea0c85344d7d7f295aea1c2353621f02db78dbccd888a0a21a0508ac330659`.
Use a disposable rooted test device and verify the file hash before attaching.
Do not put credentials, account data, or match captures in this directory.

A later lab match reproduced a recorded mismatch immediately after scene
197064. Its first two differing contributions both belong to the bot team's
goalkeeper. At adjacent final recorded frames the goalkeeper's positions
differed by about 2.7 metres; this identifies the divergent state, not why
the divergence occurred. The original game and bot matched on a preceding
celebration.

In that failing recording, the bot reported that the observed scene used its
loaded game timeline. The full-game and bot scene-start flags agreed at the
observed start; that does not prove every internal scene value was identical.
The later detailed goalkeeper and target observers were not attached during
this failure, so the saved record does not establish which earlier field or
update caused the goalkeeper to diverge.

A later 2–0 lab match had 289 shared verified checks with no differences,
including two other scenes, then stalled during the halftime transition. A
further match had 125 shared verified checks and 250 shared goalkeeper
component records with no differences, including two other scenes. Neither
reproduced scene 197064. These passes do not establish a fix. Missing player
mesh data was observed on the bot in both passing tests; it is therefore not
by itself proof of the failure's cause. The recorded actors are not yet mapped
one-to-one to the target goalkeeper.

A subsequent match reproduced a mismatch during a free kick. The observed
22-player position snapshots matched through frame 7385 and only the bot-side
goalkeeper differed from frame 7386. The checks first disagreed at frame 7398,
and a stop request followed a second disagreement. During a 12-second window
around the split, the packet capture showed 328 distinct packets in each
direction at both local forwarding points, within 24 ms of one another. This
does not prove socket delivery or explain why the keeper moved differently.
The detailed keeper decision probe was attached after that failure, so these
records do not identify which earlier controller decision caused the split.

`keeper-observer.js` and `run-keeper-observers.py` are experimental read-only
Frida probes that break the goalkeeper's checksum into individual terms on
both engines. `test-keeper-observer.js` checks the observer's filters and
checksum-delta recording with mocked Frida calls. `compare-keeper-details.py`
compares the emitted terms; initial matched-frame samples agreed. The separate
`scene-target-observer.js` probes scene-target dispatch. These tools have not
yet identified or verified a fix for the failure. Match captures and account
information are deliberately excluded.

`watch-target-celebration.py` is a disposable-lab helper that saves a local
snapshot when the observed scene 197064 occurs. Its hard-coded paths and
container name are specific to the original lab. The snapshot contains match
diagnostics and must be kept private; it is not part of this backup.

`initial-snapshot-diagnostic-fix.patch` is an isolated candidate for a
separate lab startup crash. It applies to Peerlink-Bot commit
`2b748dc0afd82f473525c1fd8cd8a175f38e370d`, which is backed up on the
`backup/peerlink-celebration-test-20261124` branch. The candidate removes the
unsynchronized initial checksum read and captures the first verified native
checksum instead. The native diagnostic fixture and original checksum
verification pass; it has not been tested in an Android match or released.
It is not a fix for the goalkeeper disagreement.

`player-mesh-observer.js` is an experimental read-only probe for the game's
original player readiness function. `mesh-options-probe.js` scans for selected
component classes and reports aggregate visibility-based animation settings;
object lifetime and matching still require validation. During one additional
lab run, the bot process crashed inside the separate initial snapshot diagnostic
before gameplay. `suppress-lab-initial-snapshot.js` is a version-checked,
lab-only override of that diagnostic for further research. It does not modify
the distributed APK and does not change the game simulation. The isolated
source patch above is not yet proven on Android.

The scene target observer records both keepers' assigned roles, the movement
controller's advance decision, and the position used by the match. After a
verified scene plays, it continues until another verified scene replaces it.
This includes restart scenes, not only celebrations. The 11.1.1 scene
preparation call occurs in state 5; the later playback call uses state 8.
The two keeper movement decisions and raw checksum words are also sampled on
observed command frames without requiring a scene to have been detected. The
two words' meanings are not yet established. These additional continuous
readings need an overhead measurement on the target Android device.
The recorder also observes a version-checked native setter that can change the
first differing keeper checksum word, recording its caller and requested
value. This setter is not necessarily the only possible writer, so a missing
event does not exclude a different write path.
The runner
writes the same capture ID on both sides. Compare matching frames per capture
with `python3 compare-keeper-handoff.py game.jsonl bot.jsonl`. The default scene
is 197064. Use `--scene` to compare one of the other celebrations and `--capture`
when the files contain multiple paired runs.
Use `--scene all` to include samples outside a detected scene.

`node test-scene-target-observer.js` exercises the recorder and comparison with
fixture data for a role difference, a controller hold, and a missing reading.
This covers the diagnostic logic. Live capture on a paired Android lab is
still pending.

The scene observer also records a read-only per-observed-command-frame
snapshot of all 22 player positions and ball position/velocity in both
engines. It uses the same player-update boundary on both sides, records gaps
and rewinds, and never invents a missing frame. Availability, overhead, and
the comparability of the boundary must still be verified in a live match;
this is not a complete dump of every internal game variable. The two-sided
runner flushes events continuously, reconnects after engine restarts, and
tags each side with the same capture ID.
