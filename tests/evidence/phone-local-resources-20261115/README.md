# Reuse local resources before an explicit download

The earlier build downloaded every missing pinned match pack before room
creation. Its 47/47 status covered selected installed CPK files, not the 63
additional match/UI resources. This made a roughly 2.2 GB transfer implicit and
left existing downloaded phone files unchecked.

Candidate `7abcf97` checks verified app-cache copies, installed game assets,
readable game directories and the existing Prime helper when available. Only
allowlisted names with matching byte count and SHA-256 are installed. It reads
source game files without modifying them. Helper copies use a temporary,
write-only content URI into app-owned cache; the URI is revoked after copying.
The app then validates the bytes before replacing a previous cached pack.
Unmatched or unreadable sources remain missing; they are not declared current
or usable. Discovery logs include available/missing file counts, remaining bytes
and inaccessible helper roots.

Downloads default to off. Missing files produce a separate room state showing
available/total counts and the remaining MB. Check phone files again retries
local discovery; Download missing files explicitly starts a preparation that
may fetch only the remainder. A preexisting active preparation cannot start a
second simultaneous room request. An unsupported engine is rejected before a
large resource transfer is offered. Resource-preparation failure stops its
foreground lifeline and keepalive. Native preparation/room creation still wait
until the exact resource set is verified. Progress reaches the room screen and
the old 47-file counter now says installed game packs.

CI `43761775854` passed the APK build, JVM tests and native policy checks.
The JVM regressions exercise matching cache/local reuse with no network calls,
changed local copies, exact missing-byte reporting without a network call,
explicitly selected remainder-only downloads, manifest restrictions and failed
or corrupt transfer preservation. All eight real Android framework-host tests
passed on each of API 34 and API 35. The new shell-copy test copied a fixture
through the normal provider grant, verified that the write grant did not allow
reading, revoked it, and rejected later access. These are small controlled
fixtures; they do not prove access to the Infinix's installed game directories.
The platform result and artifact hashes are retained in this folder.

Publication `43762370944` reused the successful CI artifact. The released APK
hash matches the source artifact. The original native match/input host,
Java game host, original engine hash and 63-file resource manifest are unchanged
from `6c7d0a9`; the earlier two-engine match observations remain separate proof.
This candidate was not used for a new full native phone-game match.

This change controls extra internet use; it does not shrink the approximately
2.2 GB full resource set or prove every pack essential. It does not claim the
pinned set includes all current live updates. The actual matching file count
and helper accessibility on the physical Infinix remain to be observed.
