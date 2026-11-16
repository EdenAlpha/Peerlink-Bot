# Original match-stop observer: APK verification

Source `886038499792d7a9c29035fb262a188901212f10`, version 5.0.19,
CI run 43961452310, artifact 14274007370. Exact installed APK SHA-256:
`391516c150e5edb338e15c65c67da1ac5140d03ad1c1900047f70eb8aed19edf`.

Both original engines constructed 22-player matches and observed ball movement
from automatic home-only Pass. Both instrumentation runs passed, with 150
samples each and no manual controller probe. The stop observer and expected
native controller were verified in both processes. Native fixture checks also
cover original arguments, return value, errno, normal frames, both stop paths
and rejection of an unexpected vtable.

The complete hold also records a later simulation stall: away's failure latch
becomes 1 at sample 127, with command frame 1821; home waits for input at frame
1824. No command-stop event or native model/channel failure was recorded.
These states are retained in `match-proof.json`. The successful kickoff/input
assertions do not establish an uninterrupted full match.

The capture contains 25,880 boundary events, zero recorder drops/errors and a
completed flush. Every forwarded payload matches its recorded input. Five
48-byte game packets on each route preceded the first engine transmission;
they were received before the engine became available. The graphics-free bot
lacked all 63 extra packs and downloaded none.

This lab uses the production ferry/native backend, socket-pair TUN, owned TLS
TURN and a hosted rendered reference peer. It does not reproduce the stock
foreground game or physical Android VPN. The earlier phone export's stop flag
at 21:40:50 remains unexplained; the new logging distinguishes the original
local request, outgoing stop and synchronized-input stop paths. A synchronized
input event alone is not automatically attributed to the remote participant.

Raw captures, account inputs and session material remain private.
