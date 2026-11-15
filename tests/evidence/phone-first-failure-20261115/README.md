# First native failure diagnostics

APK `3ba7a82` passed [Android CI 43686079091](https://github.com/EdenAlpha/Peerlink-Bot/actions/runs/43686079091): app JVM tests/build and Android 14/15 host checks. The retained APK, whose hashes are in `build.json`, was installed without rebuilding on ARM64 Android 15 lab run [43681945022](https://github.com/EdenAlpha/peerlink-efootball-disconnects/actions/runs/43681945022).

The original pinned 11.1.1 engine and owned inputs were used in two Android users. The native observer server was stopped before installation/testing; these results use the packaged APK with no native hooks, replaced callbacks, readiness writes or synthetic game acknowledgments.

Both original configured transports opened channels 14/15, kept them open for three seconds, and delivered diagnostic messages to the main process log. In a second case, B intentionally stopped its real engine after that hold. A then waited using only its exported log buffer, without driver/native snapshot RPC polling. Its service monitor independently reported:

- First native failure at monotonic time 5163452 ms, 101 ms after its preceding sample.
- Session 5, both channels 6, model error 1, native reason 1 and handler absent.
- Previous sample: session and both channels 4, model complete, no error, handler present.
- `hostStopRequestedAtMs=0`; packet ferry still armed and engine attached.
- Last game packet approximately 30 seconds earlier, while the engine continued submitting outgoing datagrams.

`exported-diagnostics.txt` contains only the app's diagnostic/flow records, captured through the production Messenger path. A successful UDP send means acceptance by the local socket, not delivery to the game application. The initial six `game_to_engine_no_socket` drops happened before the engine's return address was learned; the normal connection still completed. Cumulative drops must be read with the timeline, not automatically blamed for a later failure.

Native reason decoding is based on original `7a1f508`, `7a1edd4` and `7a2131c`. The global reason is `a5bb198`; model sync result is `+a34`; database-check configuration is `+a30`. The monitor does not call the SDK verdict routine, whose original implementation also changes internal bookkeeping. Diagnostic reads do not advance the engine or change its configuration.

This proves normal-pair compatibility and reporting of a real controlled transport failure. It does **not** reproduce or fix the user's full-game interoperability fault. In particular, the user's last export had model phase 6; this controlled established-peer loss left phase 5 with error 1. A generic native reason cannot identify the initiating side, so the report explicitly preserves `disconnectInitiator=unresolved`. The next physical-phone export is needed to distinguish transport loss from native model/data validation errors.
