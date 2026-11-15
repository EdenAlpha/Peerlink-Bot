# Original-host to phone pre-match audit

This audit compares the retained successful QEMU full-application run v278 with
the phone adapter at `65ecefe2134ef0d4466ef7420b8dfe8c40d8f60f` and its follow-up
native user-data completion guard. Source parity,
an observed native state, and a successful real-game match are different claims.
An entry marked implemented has not necessarily completed on the user's phone.

The user's latest export, `peerlink_match_1794743389364.zip`, reached connected
channels and completed initial native metadata synchronization with database
checking disabled according to the server setting. It did not reach playable
match state. At 12:49:06.812 the first sampled failure was native reason 1,
synchronization result 1, handler absent and session/channel teardown. Packets
were still passing in both directions immediately before that sample. No local
host stop had been requested. The native transport-loss result does not identify
which side initiated the disconnect or which preceding loading dependency failed.

| Step in the successful original-host sequence | Phone implementation and evidence | Remaining verification |
| --- | --- | --- |
| Pin the original executable and stage its actual game inputs | SHA-256 guard for the supported 11.1.1 engine; private working files checked against installed source. Latest phone audit passed 108 files and 44 native table layouts. | Private downloaded game updates are outside this audit. |
| Create Android/JNI host, native surface, kernel and frame loop | Framework-created NativeActivity host and private 320 × 180 surface. Original application owns its updates; no competing manual frame tick. | The QEMU custom host and Android framework host have different lifecycle paths. |
| Deliver resume and window geometry | Framework callbacks exist. Separate lab experiments delivering the late original resume callback and content rectangle did not clear the observed loading stall. | Those experiments do not establish exact lifecycle equivalence. Neither speculative change is included as a fix. |
| Build original database services and start match-data resources | All 44 original builders run; the same original match-data start function `0x68c3768` is used. | QEMU also explicitly called the original poll function. Its recorded immediate readiness result was zero; this was not itself proof of a completed load. Native dependency completion must be observed. |
| Configure account, clock, server policy and authenticated transport | Existing UID/server context, original SDK clock and transport configuration. Server YES/NO revision policy is applied before model creation. Latest physical-phone metadata synchronization passed. | Matching inputs in a two-bot lab do not verify every field supplied by a current real game. |
| Open original session and channels; report connection to the server | Original session/channel/model states gate the connection report; opaque addresses remain unchanged. The follow-up status-polling correction removes calls to the active SDK operation from snapshots and removes the redundant legacy verdict watcher. | The latest physical run passed this stage. The corrected read-only observation path needs its own packaged test. |
| Supply consistent home/away participants | Production bot is the room creator/home. Tests previously made both instances home; `7ac8f1a` supplies one shared home UID and distinct self UIDs. | Verify both native side assignments in the packaged Android test. A both-home lab is not a valid full-match comparison. |
| Complete initial metadata synchronization | Original model phase 5, complete flag, no error and connected channels gate the later setup. | This is initial metadata completion, not completion of full squad transfer. |
| Report pre-play phase to the server | `7ac8f1a` adds the omitted `CMD_CHANGE_GAMEPHASE` / `DATASYNC` request before native match setup; requires `NOERR`. MessagePack body is compared against an independent captured fixture. | This is a confirmed omission, not a proven sole cause of the physical disconnect. Fresh real-game acceptance and its effect on loading remain unverified. |
| Create and finish `TaskSyncUserData` | QEMU observed state `4` before initializing match resources/coordinator. Read-only tracing of the earlier phone adapter confirmed it requested the pre-match process while this task was still at state `1`. The follow-up guard waits for original state `4` before resource/context/coordinator setup. It re-finds the attached task under the native lock before reading, and never writes completion. | Test the guard in the packaged APK. The earlier trace later reached state `4` while squad transfer remained at state `2`; an ordering mismatch alone does not prove the sole cause of that later stall. |
| Initialize resources, apply live game ID/session/category | Same original initializer and live-context adapter; verifies UID, session keyword and server game ID. | The optional lab loading probe uses retained private context, not fresh authorization for a server match. |
| Initialize match coordinator, restore own squad and apply sides | Same original coordinator functions, squad adapter and online-model application before requesting `Online/Match/MatchProcessPreMenu`. | Observe completion of the native child tasks. A requested scene does not mean its prerequisites have completed. |
| Transfer full squads and run secondary synchronization | Original pre-match task owns these operations. Read-only lab tracing reached pre-match body state 1 with squad-transfer state 2. | New `MATCH_LOADING` export records pre-match, squad-transfer and secondary-sync task states. Previous physical exports did not contain these details. |
| Enter the actual match preview | Original flow owns scene entry. QEMU needed an idle menu retry on its game thread. | Phone has not been observed reaching preview in the controlled loading probe. The QEMU-specific temporary menu hook is not blindly ported. |
| Submit preview Continue and start the native match | The same guarded original `proceed` event adapter is polled; it requires the actual preview leaf and no conflicting pending transition. Original engine owns its start-game request and response. | Neither a fabricated readiness flag nor screen tapping is used. Real-game progression past preview is unverified. |
| Enable permitted controller and submit Pass | Original controller registration/setters and private gamepad events; input test requires playable match conditions. | Physical-phone kickoff and visible response to Pass remain unverified. QEMU input success does not prove phone input response. |

The flow-frame byte at `+0x50` is a completion signal, not a "process started"
flag. Startup/history frames can remain in the flow stack after successful
progression. Their presence alone does not identify a blocker. The production
export calls this byte `leafCompletionSignal` to avoid that misinterpretation.

The exported VPN counter is also incomplete as a total-traffic measure. The
latest capture contains 483 VPN packets; the direct-host path separately counted
thousands of packets in each direction. No capture-queue drops were reported.
Two early `game_to_engine_no_socket` drops did not increase during the later hold.

No kickoff claim follows from this audit. The remaining acceptance criterion is
a current, server-authorized real-game match that passes full squad transfer,
preview and native start, then visibly responds to a bot Pass input.

The `65ecefe` APK passed an unhooked ARM64 Android 15 matrix: installed-file
corruption detection/repair, connected pairs with both YES and NO database
policies, correct original home/away assignments, and a controlled peer stop
with exported diagnostics. The user-data loading trace was a separate read-only
instrumented experiment; it was not part of that unhooked matrix and used
retained private context. Updated guard validation must be recorded separately.

The first `8b68c57` guard-validation attempt passed file repair and the YES pair,
then crashed during the NO pair before reaching match setup. The original engine
backtrace ends at `0x6922030`, through `0x7e014e0`, called by the host's
`nativeSnapshot` on `engine-runtime`. Inspection of `0x7e013ac` confirms that the
supposed SDK verdict query invokes network operations and changes bookkeeping;
it is not a passive getter. The follow-up removes that operation from every
snapshot and from the compatibility status query. Native session/channel/model
state remains the source of readiness; no positive verdict is manufactured.
The redundant legacy status thread is removed, leaving the existing independent
monitor and driver. This identifies the operation that crashed in the lab; it
does not establish the initiating cause of the earlier physical-phone teardown.

The final candidate is `28b0fc42261796147526ea7cc0a44308cc2e77ea`, from
[CI 43717969655](https://github.com/EdenAlpha/Peerlink-Bot/actions/runs/43717969655).
App compilation/unit tests and Android 14/15 framework host tests passed. The
exact retained APK was installed in the ARM64 Android 15 lab and passed file
repair, the user-data ordering guard, YES and NO policy pairs with correct
home/away roles, and controlled peer-stop reporting. No native observer was
running during those checks. `build.json` identifies the artifact and APK hashes.

The guard test checks ordering, not match completion. Both engines observed
native user-data state `4` before pre-match initialization. Their status samples
kept the old active-query slot at unavailable (`-1`) and the native connection
remained connected. The pre-match task subsequently reached state `4` with
squad-transfer task state `9`, the original error path. The earlier squad receive
wait was left, but the full match did not load. The current evidence does not
distinguish team validation from the native start-command failure paths, and the
fixture's server context was retained rather than freshly authorized.
`final-loading-observation.json` and the per-side details preserve this failure.
No preview, kickoff or visible Pass is claimed. A green guard matrix must not be
reported as a successful full-game match.
