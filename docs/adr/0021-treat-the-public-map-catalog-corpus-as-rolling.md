# Treat the public map catalog corpus as rolling

The latest map list and style documents fetched from `https://dashboard.lascade.com/travel_animator/v0/maps/` are authoritative for corpus validation. Rentile does not fail merely because style content changed and does not commit catalog responses, style JSON, provider URLs, or credentials. Each run generates a capability report and still fails closed when the current renderer cannot prepare or render a reachable retained construct. The versioned Coverage Manifest remains the stable definition of expected map IDs, zoom, coordinate, source, seam, and capability cases; this deliberately trades reproducible historical style bytes for automatically validating intentional live style changes.

The catalog is paginated. The harness follows bounded same-origin `next` links, requires the declared total to equal the collected unique map count, and requires the resulting ID set to equal the Coverage Manifest. Pagination cannot silently reduce corpus coverage.

No catalog secret or base64-encoded index is required. The full corpus gate runs for pushes to `main`, explicit workflow dispatches, and the Maven Central release gate. Ordinary pull requests retain the credential-free unit and fixture suite; the catalog gate remains separate because it performs live provider requests and can be substantially slower.
