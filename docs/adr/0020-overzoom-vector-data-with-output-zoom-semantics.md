# Overzoom vector data with output-zoom semantics

When an output tile is above a vector source's maximum data zoom, Rentile reuses the single covering maximum-zoom source tile and transforms and clips its geometry into the requested output-tile coordinate space. Style-layer zoom ranges, filters, expressions, paint, layout, and independent-icon placement continue to evaluate at the requested output zoom rather than the source-data zoom. This matches the expected high-zoom map behavior without inventing unavailable source detail, while layers whose own zoom ranges have ended remain absent.
