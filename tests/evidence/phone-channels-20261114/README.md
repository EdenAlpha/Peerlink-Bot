# Original Android engine channel test

APK source: `3ddc5615d60181bec23562e7bfb96434604f7616`.
CI: [43648763321](https://github.com/EdenAlpha/Peerlink-Bot/actions/runs/43648763321).

The previous phone driver waited for the base session and data channels 14/15
before constructing the original online model. That model constructs the
GameDataConnection which opens those channels, so the wait could not finish.
Constructing the model immediately after opening a still-connecting session
also fails: the original data constructor cannot yet obtain the negotiated
configuration, and its model observes an error and removes its handler.

The corrected order observes the original base-session state 4, supplies the
owned participant/squad inputs through original constructors/setters, then
waits for the original data channels to report state 4. Later game ID/session
inputs reuse that model. No readiness or connection fields are assigned.

The exact packaged APK and its test APK were installed as signed updates on
ARM64 Android 15 Redroid. Two Android users ran separate original engines with
the owned lab account IDs and squad DTOs. A test-only pair of UDP sockets
forwarded unchanged original SDK payloads between their ephemeral endpoints.
The endpoint/NAT inputs are an isolated local fixture, without an authenticated
server room or match ID. Each fixture instance uses the bot-host input path;
this test does not verify opponent home/away assignment or complete team data.
There were no diagnostic hooks or native-memory changes during these tests.

Both engines reported base-session state 4 while channels 14/15 were still 0.
After original handler construction, both channels reached 4 in each engine;
both remained connected for the five-second hold. Both paired tests passed.

The no-answer test rejected premature model creation and remained unconnected.
The ferry transport and original-engine/controller startup tests also passed,
including all 44 database tables and the 15-second startup hold. The APK/JVM
build and seven framework tests on each of Android 14 and Android 15 passed.

This establishes local native session/data-channel connection with the phone
host. A fresh authenticated match against the user's foreground eFootball,
kickoff, visible Pass response, and sustained physical-phone memory use still
require a real device match. The uploaded phone capture also contained a
capability refusal; these local tests do not establish its exact cause or prove
that the user's next handshake will succeed.

APK SHA-256:
`087fdfec25efd70cc4630db693c88da93c49a2ad11336b75e38d3bfdf76d4899`.
