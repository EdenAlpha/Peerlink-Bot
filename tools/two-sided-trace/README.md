# Two-sided celebration investigation

These are **experimental lab tools** for comparing the original eFootball
11.1.1 engine with PeerLink's bot in the same match. They do not change the
PeerLink APK. One live lab match produced 194 verified full-game checks whose
checksums and 71 contributors matched the bot through the first half. That
match ended during the halftime transition; no goal was scored.

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
the full-game side; its shot and celebration pause were checked only with
simulated data, and its additional native control getters still need live
verification. It is not PeerLink's bot logic.

The native offsets apply **only** to the original 11.1.1 `libUE4.so` with
SHA-256 `2cea0c85344d7d7f295aea1c2353621f02db78dbccd888a0a21a0508ac330659`.
Use a disposable rooted test device and verify the file hash before attaching.
Do not put credentials, account data, or match captures in this directory.
