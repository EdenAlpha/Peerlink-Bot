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
