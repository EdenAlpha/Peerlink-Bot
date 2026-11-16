# Initial match-start stall: diagnosis and capture coverage

All three physical exports identify APK source `a80521e`, version 5.0.17.

| Attempt | Observation | What is established |
| --- | --- | --- |
| `1794857898639` | Pitch loaded; seven Pass attempts; every sampled ball position stayed at the centre. At 20:37:38.792, native model reason 1 and both data channels failed. Traffic was flowing immediately beforehand. | Bot process stayed alive. The original trigger and disconnect initiator remain unresolved. |
| `1794859228033` | At 20:59:29, Android's process exit record explicitly reported `low_memory`. | Android killed the bot during loading. This does not establish the cause of the first attempt. |
| `1794859869997` | Bot remained alive, with connected channels and a stationary centre ball. Game packets stopped around 21:10:49; bot packets continued until export. | User confirmed switching to PeerLink before the match ended. The silence cannot establish a forwarding failure; no final native disconnect appears in this attempt's export. |

The earlier PCAP recorded only 5–9 packets per attempt because most bot traffic
used the direct socket route. The ferry's packet counts therefore contained
much more information than its packet capture. Source `5418dfa` records the
unchanged UDP payload at GAME_RX, ENGINE_TX, ENGINE_RX and GAME_TX on both
routes, in `bot_transport.csv`. Its export snapshot includes drop, write-error,
cap and flush-completion information. A bounded queue keeps file writes and
hex formatting off forwarding threads.

The same build reports the original simulation service's wait result/reason,
failure latch and tick gate, using its original getter at 0x701289c and passive
scalar reads. None of these fields is assigned to force play to start.

The retained 5.0.18 APK has SHA-256
`5ed9ed33ef1e37302d6609e5f752242e05735720747eba335c872b5a860c4900`.
CI run 43945346968 passed unit/native checks and Android 14/15 host checks.
The exact APK reached constructed matches on both lab engines, and automatic
home-only Pass moved the ball without a test-only controller enumeration.
150 samples per role and 30,624 captured boundary events were retained. All
forwarded payloads matched byte for byte; capture drops and write errors were
zero. The bot lacked all 63 extra packs and downloaded none.

The lab uses a hosted rendered reference peer, production ferry/native backend,
socket-pair TUN and owned TLS TURN fixture. Late native wait states are retained
in `match-proof.json`; this is a control/capture verification, not proof of a
complete uninterrupted match or of a fix for the stock phone game's initial
stall. Raw packets, session inputs and credentials remain private.

The subsequent memory-cleanup source `9e127bb` is not part of this tested APK;
its CI verification is separate. No memory-kill fix is claimed for 5.0.18.

## New physical export: 1794861730087

This export is from 5.0.18 / source `5418dfa`. Its last attempt contains 9,120
capture events, with no capture drops or write errors. Pairing each route and
direction shows byte-identical GAME_RX → ENGINE_TX and ENGINE_RX → GAME_TX:
the host route contains 1,865 and 2,690 pairs respectively, and the VPN route
contains 2 and 3 pairs. This establishes forwarding at those recorded
boundaries, not receipt or acceptance by the remote native engine.

The first observed stop is earlier than the generic transport failure:

| Phone time | Observation |
| --- | --- |
| 21:40:40–45 | Original simulation wait reason 10, result 2; the match is constructed but sampled ball position remains at the centre. |
| 21:40:50 | Original simulation wait reason 3, result 1, with failure latch 0. |
| 21:41:00.507 | Last captured datagram from the phone game. |
| 21:41:30.688 | First generic native model reason 1 / channel failure. Bot process remains alive. |

Pinned build-B Ghidra output resolves reason 3 to command-controller
vtable+0x68 (0x70165f8), which reads its stop latch at +0x49. Function
0x701794c sets that latch. Its callers are the local outgoing-command path
(0x7017560) and the synchronized input-command path (0x7018738, command 0x50).
The earlier reason 10 is an input-frame readiness mask and also occurs during
normal play; its presence alone does not identify the cause.

The completed comparison with reversed account ordering reached ball movement
on both roles. Account order is therefore not an established cause, and no
participant-role change was made. The original stop trigger remains unknown.

The next candidate adds pass-through observers for the local stop request,
outgoing command processing and synchronized input application. They preserve
arguments, errno and the original input return value, and record the first
stop transition plus the native command-frame/missing-input masks. An input
path event is labelled `applied_input_command`; it is not automatically
attributed to the remote phone because synchronized inputs include both
participants. No stop flags, readiness gates or input queues are modified.
