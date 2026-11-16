# Physical-phone simulation check stop

The 5.0.19 user report contains two distinct failures. One bot process was
terminated by Android with `low_memory`. A later attempt kept the original
transport connected and the bot process alive, but its simulation stopped.
These failures must not be treated as one diagnosis.

For the latter attempt, the original local stop setter was called with return
address `0x70539e4` at command frame 56. Its synchronized stop command was
applied at frame 58. The pinned 11.1.1 engine's original checker at `0x70539ac`
calls that setter only after the original comparison at `0x7053ea0` reports
persistent state-check disagreement. The check interval is 27 simulation steps.
The old report contains no checksum values and cannot identify the first
underlying state difference. A routing timeout is not the observed stop trigger.

A separate controlled Android 15 comparison used the unchanged released main
APK (SHA-256 `391516c150e5edb338e15c65c67da1ac5140d03ad1c1900047f70eb8aed19edf`).
The minimal headless bot and fully resourced rendered reference agreed on 32
consecutive original state checks even when the reference retained its original
disabled controller-test setting. This rejects that controller-setting hypothesis
for this controlled case. It does not prove compatibility with the user's stock
foreground game or identify the physical phone's first differing field.

The full-file comparison is being tested separately. Merely restoring all 63
files in the current headless startup did not reproduce the historical full-file
kickoff: home remained at outer state 12 without players, while the rendered
reference loaded 22 players. No post-kickoff state comparison was possible in
that run. The earlier rendered full-file configuration is the next control.

The new observer captures the original comparison's local/peer values and frame
numbers before the context goes out of scope. It also records the original
checksum contributors, verified scalar settings, and ball/player position bits
at initial live play and at the stop request. Recomputations omit the original
checksum service's invocation-counter increment. Snapshots are diagnostic;
the original comparison, stop request, and synchronized inputs are preserved.
The extended native fixture checks both checksum arrival orders, rejects
unverified contexts, and verifies that only the original stop setter changes
engine state. ARM64 application verification remains required before release.
