# Report peer transport before waiting for match data

APK source: `f1fd6f06f7013d9a444c4c11c7b86d43db88ef92`.
[Android CI 43663921495](https://github.com/EdenAlpha/Peerlink-Bot/actions/runs/43663921495).

The new physical-phone export confirms progress after the candidate-routing
fix: the original SDK verdict reaches 1 and the base session reaches state 4.
The original match-data handler is constructed, but channels 14/15 remain 0.
The driver waits for those channels and never reaches its connection-report,
game-ID or later native match-setup steps. The eventual timeout is therefore
later than the previous handshake refusal. `phone-observation.json` retains
the relevant native states without the user's addresses or account IDs.

The corrected driver waits for the original SDK verdict 1 and session state 4,
prepares the original handler, then proceeds to its existing server report and
game/session setup sequence. `PeerConnectionPhase` is shared by that driver
and the native ordering test. Both data channels, actual match construction,
an on-pitch ball and controller settings remain required for gameplay inputs.

Three JVM regressions cover the captured session4/SDK1/channels0 state,
session4 without SDK confirmation, and an unanswered peer. The first permits
server progression while gameplay remains blocked; the others refuse it.

The exact APK was installed on ARM64 Android 15 Redroid. The paired native
test uses actual production `PeerSlot` and the phone's asymmetric peer-profile
values. The counterpart deliberately holds its original data-model creation
until a separate local test-control signal. The shared connection phase
returns with SDK verdict 1, session4 and channels3/3. The signal then releases
the counterpart; both original engines reach channels14/15 state4 and survive
the five-second hold. The original frame counter is 740 at confirmation.
Retained logs include both ordering markers and both production-ferry markers.

The signal is a local readiness barrier, not a Konami response or a game
protocol packet. The experiment verifies the ordering and readiness policy;
it does not prove why the live server/opponent was waiting. Server acceptance,
fresh game-ID setup, physical-phone kickoff and visible Pass response still
require a fresh phone match.

The transport regression, all 44 database tables, own-controller startup,
15-second startup hold and unanswered-peer guard also passed. The APK/JVM
build and seven framework tests on each of Android 14 and 15 passed. No
diagnostic hooks, manual engine ticks or native state patches were used.

After native match setup, sampled native states now enter the match export
every five seconds. Snapshot index20 reads the original application's frame
counter, allowing a later stalled engine to be distinguished from data-channel
or server progress without changing its heartbeat.

APK SHA-256:
`c47c92cfcfbc27f29f2459ed3eaf65bd156ff89e04ae4977bb730ca25acdc00d`.
