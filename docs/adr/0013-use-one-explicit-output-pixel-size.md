# Use one explicit output pixel size

Rentile v1 will expose `outputSizePx`, defaulting to 512, as the actual width and height of the square output PNG instead of exposing an ambiguous output-tile-size and caller pixel-ratio pair. V1 accepts exactly 256 and 512; every other value fails validation before acquisition or rendering. Style-space scaling derives from `outputSizePx / 512`; sprite-entry pixel ratios remain internal atlas metadata, and raster pass-through requires an exact source/output pixel-size match.
