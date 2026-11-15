# Preview resource gap

The retained QEMU host was not lost. Its content directory contains the five
base Unreal files, three `pc0010` files from the installed APK, and 63 additional
match/live UI resource files. The old Android preparation copied the five base
files and the CPK/database files. It omitted the additional IoStore packs.
Consequently, its successful copy audit did not establish that match resources
were present.

The original loader requests
`/Game/Assets/ui/Data/Widget/General/MatchFlow/EnvironmentPreview/EnvironmentPreview.EnvironmentPreview_C`.
The package's original ID, `cd39b5bb6cc01b4d`, is present in
`pc1000_mobile_and.utoc`. This is a downloaded match pack, not one of the five
files extracted from `pc0000_mobile_and.pak`.

In the Android 15 lab, the title widgets loaded but this preview request
completed with a null asset. The menu remained at actor state 5; its pending
preview object remained at widget state 0. Repeating the original content-rect
or late resume callback did not supply the missing asset. Retrying Continue
while the asset was absent did not advance the match.

After staging the original additional pack bytes, the same candidate loaded
`EnvironmentPreview_C`. Both original menu actors reached state 6, and the app
queued its original Continue event on the actual game thread. No menu-ready,
network-ready or match-progress field was assigned by the test. The external
observer read asset/menu results; it did not override the load result.
`preview-asset-observations.json` preserves the decisive before/after events.

The 30-second restored-file observation did **not** reach a playable match:
the sampled match outer/core/player values remained zero. This clears the
identified missing-preview dependency; it is not a kickoff or controller claim.

The replacement preparation checks the installed auxiliary IoStore files and
all 63 pinned additional resources before native startup. Downloads happen
before the bounded startup IPC and before creating a room. Corrupt, incomplete,
oversized or truncated files are rejected before replacing an existing file.
Native paths link to the verified private copies when supported. The filtered
companion release contains only the allowlisted game resources, excluding
accounts, device files and session dumps. `required-resource-inventory.json`
records its 2,184,159,958 bytes and all file hashes. Private updates in the human
game's saved data remain outside this audit.

Startup comparison also found that the QEMU host called the original Java
resume notification after kernel/data initialization. The original native
callback at `0x6abc790` returns unless kernel phase is exactly 1. The Android
host's framework resume precedes surface creation. Candidate `b829770` therefore
delivers the original game-side resume notification once on the main looper
after kernel/data startup. It does not repeat the framework Activity lifecycle
or manually advance the game loop. Its end-to-end match outcome must be recorded
separately from this source-order correction.

CI runs `43746627992`, `43749414409` and `43750449030` passed compilation/unit
checks and the Android 14/15 framework-host checks. A later fresh native-pair
attempt using candidate `eb5bf16` passed source checks and metadata connection,
but fixture input delivery and the lab time limit prevented a complete match
observation. It supplies no kickoff evidence. The fresh full-resource test and
exact installed artifact hashes will be retained separately.


## Full-resource Android playable-match observation

A fresh Android 15 pair running the exact `b829770` CI artifact subsequently
reached original outer state 17, core state 54, all 22 players and an on-pitch
ball on both sides. The first playable observation occurred about 190 seconds
after match setup; both engines remained playable for over 100 one-second
samples. Their final ball positions agreed. `android15-playable-observation.json`
records the exact artifact hashes, first/last states, observed times and limits.
The original application owned simulation/network ticks; no external native
observer was installed in this run. Fresh owned account/server context was
supplied only after the original engines completed their native channel setup.

The overall 600-second JUnit limit expired near the end of the 300-second
loading observation because setup had already consumed time. Both test methods
therefore report TIMEOUT, **not a passing test run**. Native playable observations
preceded that timeout; test cleanup stopped the engines. This proves the Android
lab milestone, not a physical Infinix match, complete gameplay, or Pass response.

The automatic Pass gate remained false: the original controller source was 1,
but the optional settings object was absent. The old snapshot mapped absence to
0 (disabled). The retained QEMU controller observation also reports that object
absent (`old_settings=255`, `new_settings=255`). The follow-up preserves absence
as -1 and accepts an enabled original source with either absent or enabled
settings; existing disabled settings, inactive sources and incomplete match
states still block Pass. The room's controller authorization remains required
by the service. The focused follow-up test samples the home side before and
after a private-engine Pass, separately from its native queue acceptance.
