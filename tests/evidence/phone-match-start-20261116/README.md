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
