# Optional peer-record size metadata

The physical-phone export after release `4389161` reached original network-I/O readiness 1, selected the configured native transport and reported original SDK verdict 1. At 05:41:18.189 (device-local time), the driver stopped with `The server's peer connection record has an invalid length`. It did not reach the server connection report, GameID request or native match setup. The sanitized timeline is in `phone-observation.json`; no raw captures or account/session values are committed.

`NativeTurnExchange` required `address_data_size` in every GET/WATCH reply. That field is sent by `CMD_SEND_TURN_ADDRESS_DATA`; a nonempty Base64 address can be returned without it. The old predicate compared a missing size (`null`) with the decoded length and threw, so an otherwise valid native record could stop the entire match flow. The failed reply body is not in the user's export, so the export alone cannot distinguish missing metadata from an explicit inconsistent size.

Commit `0979e4c` uses the decoded record length when size metadata is absent. It retains the 1..2048-byte native-input limit, exact binary contents, deduplication, and validation of a declared size when present. Size mismatches now report declared/decoded counts without exposing record bytes. Declared sizes are compared as Long values to prevent Int overflow.

Regression tests cover GET and WATCH replies without the SEND size field through the production MessagePack decoder, unchanged byte-for-byte native delivery, progression through the shared connection phase, explicit mismatches, oversized records without size metadata, and overflowing declared sizes. They do not simulate a successful physical-phone kickoff. The existing native engine/ferry implementation is unchanged by this parser patch.

Build/release verification is recorded in `build.json` after CI completes. Physical-phone kickoff and visible bot control still require a new match test.

The following phone export (`peerlink_match_1794719131390.zip`) supplied the precise values: declared payload 7 bytes, decoded buffer 20 bytes. The strict-equality check retained by this build still rejected that valid shape. Commit `eaf7f2a` follows the original native callback's separate payload count; see [the subsequent correction](../phone-peer-record-padding-20261115/README.md). This directory records the earlier, incomplete parser fix.
