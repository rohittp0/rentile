# Always return PNG

Rentile will accept validated PNG, JPEG, and WebP raster-tile inputs in v1 but will always expose PNG output bytes. Animated and other codecs remain unsupported until required by the rolling corpus and proven on every target; only a pixel-equivalent unchanged PNG may use raster pass-through, while every other supported input is decoded, transformed as required, and encoded as PNG.

Determinism is measured on decoded pixels, not compressed PNG byte identity across platforms. A content key identifies the frozen inputs and renderer semantics rather than the encoder byte stream; identical same-target inputs remain deterministic, while cross-platform acceptance uses the versioned decoded-pixel tolerance.

The canonical decoded output is 8-bit sRGB RGBA with straight alpha and transparent black for pixels receiving no rendered content. Rendering may use premultiplied alpha internally, but that representation cannot leak through the PNG boundary.
