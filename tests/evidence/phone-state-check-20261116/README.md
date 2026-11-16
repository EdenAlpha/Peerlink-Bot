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
that run. The earlier rendered full-file configuration subsequently completed 120
observations per engine: both loaded 22 players, observed 37 ball positions,
and agreed on all 56 recorded state checks through frame 1512. This confirms
the full-file control can play in the hosted lab. Together with the minimal
control, it does not reproduce or explain the stock phone mismatch. Different
server matches use different match IDs/initialization; their raw checksum
numbers must not be compared across runs as if the matches were identical.

The new observer captures the original comparison's local/peer values and frame
numbers before the context goes out of scope. It also records the original
checksum contributors, verified scalar settings, and ball/player position bits
at initial live play and at the stop request. Recomputations omit the original
checksum service's invocation-counter increment. Snapshots are diagnostic;
the original comparison, stop request, and synchronized inputs are preserved.
The extended native fixture checks both checksum arrival orders, rejects
unverified contexts, and verifies that only the original stop setter changes
engine state. ARM64 application verification remains required before release.

The first unreleased state-capture candidate (`9cbbc40`, main APK SHA-256
`ce288e66eba22815a8e5a41e9ccf94f0a43f6f87b43b3e70aed1d1cf83b0c470`)
crashed in the new diagnostic reader and was rejected. The original call path
`7053d8c -> 6affecc -> 6affee8 -> 6afff10 -> 6afff20` dereferences the map's
service handle twice before passing the actual checksum state to its helpers.
`195f869` corrects that argument. The extended native fixture distinguishes
map, handle, and checksum-state storage, verifies all 71 helper calls receive
the real state, and confirms diagnostic recomputation leaves the invocation
counter unchanged. `verify_match_state_checksum.py` separately executes the
original ARM64 pointer path and checksum fold with the pinned owned library.
The rejected build has not been published. The corrected artifact
(SHA-256 `fd19bc0ebae8dc9e932ad5c405ca8c02e5fc3238c4ed40a7573872253b653e8f`)
subsequently passed the Android 15 ARM64 normal match test: both engines
completed 120 observations, each observed 32 ball positions, and all 64
recorded original state checks agreed through frame 1728. All 71 checksum
contributors were captured and their sum/fold verified. The bot had none of
the 63 extra packs and made zero extra resource downloads. First-live snapshots
were observed at their labelled frames (87 and 312), so they must not be treated
as identical frame-zero captures. The installed APK digest was checked
directly against the retained CI artifact before the separate fault test.

A separate owned-lab fault validation deliberately changed incoming checksum
values at frames 27 and 54 after their original decoding. The original engine
requested stop at frame 55 and both engines applied it at frame 57. The actual
ARM64 wrapper captured local 33311 / peer 33310 at frame 54, the first mismatch
at frame 27, the original verdict and count, and all 71 stop-state contributors.
No native crash occurred. This validates diagnostic capture and preservation of
the original stop; it is not a natural reproduction or fix of the phone defect.

Release `phone-engine-test-195f869` publishes the exact tested 5.0.20 APK from
CI run `43981217591`, artifact `14282364682`, without rebuilding it. The GitHub
asset digest matches the installed APK digest above. See `release-proof.json`.
The phone's underlying mismatch and separately observed low-memory exit remain
unresolved; this release is diagnostic.
