# Compression measurement of the 63 pinned resources

All 63 source files were located locally and matched against the size/SHA-256
manifest before measurement. Their total was 2,184,159,958 bytes. No resources
were downloaded again for this experiment.

A complete streaming ZIP with Deflate level 9 measured 2,164,430,561 bytes,
including its headers/directory (0.90% reduction). A deterministic USTAR stream
containing the same 63 file basenames, compressed with Zstandard level 9 and
two workers, measured 2,114,162,022 bytes (3.20% reduction). Output was counted
without saving another full archive, so these are full-set measurements rather
than estimates from small samples. The original resource files were unchanged.

Neither tested method approaches a 500,000,000-byte target. This does not prove
a mathematical lower bound for every lossless compressor. It shows that the
ordinary compression methods tested do not produce the requested 500 MB
bundle. An asset-subset or repacking approach has not been validated. Lossless
archive compression also leaves the original approximately 2.2 GB expanded
resource footprint unchanged. The existing Android loader was not changed to
consume a compressed archive, and no new APK is claimed by this experiment.


## Candidate content, not a validated reduced bundle

The original registry contains 147,583 package paths. It assigns detailed
real-face assets to chunk 3611, stadium/scenery and props to 3001, a separate
Parkour mode to 4000, movies/presentation to 7000 and mixed UI to 1000. These
are concrete categories to investigate for a bot without visible graphics.
The candidate JSON retains counts, sample paths and whole-group byte sizes.
Those group sizes must not be described as established savings: registry
assignments are not a runtime dependency trace, and required/optional packages
can share containers. In particular chunk 1000 contains the match-preview
widget whose earlier absence blocked loading. No pack has been removed or
repacked by this analysis, and no under-500-MB set has been validated.
