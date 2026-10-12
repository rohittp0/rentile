package com.rohittp.rentile.internal

internal fun interface HillshadeHeightSampler {
    operator fun invoke(x: Int, y: Int): Double
}

/** Cache the small source rectangle plus its gradient border, never an unbounded source. */
internal fun cachedHillshadeHeights(
    xs: IntArray,
    ys: IntArray,
    logicalSize: Int,
    sample: HillshadeHeightSampler,
): HillshadeHeightSampler {
    val left = xs.min() - 1
    val top = ys.min() - 1
    val width = xs.max() - left + 2
    val height = ys.max() - top + 2
    // A 512px style tile may read a huge downsampled image; only cache when the rectangle is no
    // larger than the logical tile plus its one-pixel border (about 2MiB of doubles per draw).
    if (width > logicalSize + 2 || height > logicalSize + 2) return sample
    val heights = DoubleArray(width * height)
    for (y in 0 until height) for (x in 0 until width) {
        heights[y * width + x] = sample(left + x, top + y)
    }
    return HillshadeHeightSampler { x, y -> heights[(y - top) * width + x - left] }
}
