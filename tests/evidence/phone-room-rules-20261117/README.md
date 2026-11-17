# Lobby rule application candidate

The physical 5.0.20 report records the original state-check disagreement at
frame 54 (bot 30497, peer 28974), first disagreement at frame 27, then the
original local stop request at frame 56. Transport failure followed later.
Two other attempts in the report were terminated by Android for low memory.
These are separate failures.

The standalone setup constructs a fresh GameInfo input with an empty key and
copies only participants, category and live server context into it. It does
not import the room's full `match_env`. The native commissioner resets the
active CommonWork settings. Original `7a589a0` applies GameInfo rules only for
a populated supported input with a nonempty key; changing a stored time field
alone did not change the active rules in a controlled Android test.

Original lobby builder `8131aa0` supplies the key `lobby`, converts the room's
native environment through `7a5b760`, and uses `7a587f0` to set GameInfo. Native
environment conversion `7a5a958` consumes the parsed server token/int layout
produced by `7578bc8`. `7a589a0` applies the resulting inputs through original
CommonWork setters, including `8293d88` for active match time.

The candidate carries actual `CMD_GET_ROOM_INFO` rules through production IPC,
uses those original converters and setters after commissioner construction,
and checks that active match time equals the server input before entering the
pre-match flow. Rules are exported as `ROOM_MATCH_RULES`; import failure is a
specific startup failure. It does not assign match-progress or network flags.
The source game remains pinned to 11.1.1, SHA-256
`2cea0c85344d7d7f295aea1c2353621f02db78dbccd888a0a21a0508ac330659`.

This is a concrete setup omission. Native application, sustained simulation
checks, exact packaged artifact validation and physical-game compatibility
must still be tested before calling the user's disconnect fixed.
