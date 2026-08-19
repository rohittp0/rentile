# Prefer cached tile substitution before network

`ResourceAccessMode.CACHE_SUBSTITUTE_THEN_NETWORK` lets a substitution-enabled Output Tile
preparation use the exact Raw Resource cache first, then try Rentile's existing immediate-child and
ancestor synthesis against cache only, and only then perform normal exact acquisition. A successful
cached substitute is returned without transport work, carries the same provenance and content
identity as every other substitute, consumes the caller's `TileSubstitutionPolicy` allowance, and
remains recoverable through `retryExact`. If cache-only synthesis cannot build the Output Tile, the
operation falls through to `NORMAL`, including its existing network-failure substitution behavior.

The mode does not introduce a background request queue or own recovery timing. The caller retains
the Prepared Batch and decides when to invoke `retryExact`; this preserves ADR 0011's caller-owned
recovery policy while keeping cache lookup, synthesis, and exact-resource mutation inside Rentile.
For operations without tile substitution the mode is equivalent to `NORMAL`. The mode never enters
output identity because the frozen exact or synthesized Raw Resource digests already do.
