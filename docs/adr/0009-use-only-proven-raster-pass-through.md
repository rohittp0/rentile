# Use only proven raster pass-through

Rentile will implement raster pass-through early, but only when preparation and runtime validation prove that a single retained raster layer contributes an already valid PNG with matching XYZ coverage and pixel dimensions, neutral paint, no resampling or overzoom, and no other visible pixel contribution. The optimization returns the same semantic output and content identity as normal rendering; if any condition is absent or runtime bytes disagree, Rentile falls back to decode, composite, and PNG encode rather than weakening compatibility.
