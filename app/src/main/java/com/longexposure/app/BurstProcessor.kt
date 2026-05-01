package com.longexposure.app

import android.graphics.Bitmap
import android.media.Image
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Accumulates RAW_SENSOR burst frames into a single long-exposure image using
 * brightest-pixel (lighten) blending with per-frame translational alignment.
 *
 * Usage per burst:
 *   1. Call [reset] before the first frame.
 *   2. Call [processFrame] for every acquired RAW_SENSOR [Image].
 *   3. Call [getResultBitmap] after all frames to obtain the final ARGB_8888 Bitmap.
 */
object BurstProcessor {

    /** Downscale factor used to build the alignment thumbnail. */
    private const val THUMB_SCALE = 16

    /**
     * Maximum translational search radius in thumbnail-space pixels.
     * In full-resolution pixels the covered range is ±(SEARCH_RADIUS × THUMB_SCALE).
     */
    private const val SEARCH_RADIUS = 3

    // ── Accumulated state ─────────────────────────────────────────────────────

    /** Running per-pixel maximum, stored as unsigned 16-bit values in a ShortArray. */
    private var maxStack: ShortArray? = null

    /** Downscaled grayscale thumbnail of the first (reference) frame. */
    private var refThumb: IntArray? = null
    private var refThumbW = 0
    private var refThumbH = 0

    private var frameWidth = 0
    private var frameHeight = 0

    /** Highest raw pixel value seen so far across all frames. */
    private var maxPixelValue = 0

    // ─────────────────────────────────────────────────────────────────────────

    /** Clears all accumulated state; must be called before each new burst. */
    fun reset() {
        maxStack = null
        refThumb = null
        refThumbW = 0
        refThumbH = 0
        frameWidth = 0
        frameHeight = 0
        maxPixelValue = 0
    }

    /**
     * Processes one RAW_SENSOR [image] frame using a two-pass algorithm:
     *  - Pass 1: builds a downscaled thumbnail and determines the alignment offset
     *            against the reference (first) frame via Normalised Cross-Correlation.
     *  - Pass 2: applies the offset and updates the running per-pixel maximum.
     *
     * @return `true` on success, `false` if the image cannot be read.
     */
    fun processFrame(image: Image): Boolean {
        val plane = image.planes.firstOrNull() ?: return false
        if (plane.pixelStride != 2) return false   // RAW_SENSOR must be 16-bit

        val buf = plane.buffer.apply { rewind() }
        buf.order(ByteOrder.LITTLE_ENDIAN)
        val sb = buf.asShortBuffer()

        val rowStride = plane.rowStride
        val rss = rowStride / 2        // row stride in 16-bit words
        val w = image.width
        val h = image.height
        val tw = w / THUMB_SCALE
        val th = h / THUMB_SCALE

        val isFirst = maxStack == null
        val stack = maxStack ?: ShortArray(w * h).also {
            maxStack = it
            frameWidth = w
            frameHeight = h
        }

        // ── Pass 1: build grayscale thumbnail ────────────────────────────────
        val thumbAccum = LongArray(tw * th)
        val rowBuf = ShortArray(w)
        val scaleDiv = (THUMB_SCALE * THUMB_SCALE).toLong()

        for (row in 0 until h) {
            sb.position(row * rss)
            sb.get(rowBuf, 0, w)
            val ty = row / THUMB_SCALE
            for (col in 0 until w) {
                val v = rowBuf[col].toLong() and 0xFFFFL
                thumbAccum[ty * tw + col / THUMB_SCALE] += v
                if (v > maxPixelValue) maxPixelValue = v.toInt()
            }
        }
        val curThumb = IntArray(tw * th) { (thumbAccum[it] / scaleDiv).toInt() }

        // ── Alignment ────────────────────────────────────────────────────────
        val (dx, dy) = if (isFirst) {
            refThumb = curThumb
            refThumbW = tw
            refThumbH = th
            Pair(0, 0)
        } else {
            findOffset(refThumb!!, curThumb, tw, th)
        }

        // ── Pass 2: update per-pixel maximum with alignment applied ──────────
        for (y in 0 until h) {
            val sy = y - dy
            if (sy < 0 || sy >= h) continue
            sb.position(sy * rss)
            sb.get(rowBuf, 0, w)
            for (x in 0 until w) {
                val sx = x - dx
                if (sx < 0 || sx >= w) continue
                val v = rowBuf[sx].toInt() and 0xFFFF
                val idx = y * w + x
                val cur = stack[idx].toInt() and 0xFFFF
                if (v > cur) stack[idx] = v.toShort()
            }
        }

        return true
    }

    // ── NCC-based translational alignment ─────────────────────────────────────

    /**
     * Finds the best translational offset (dx, dy) between [ref] and [tgt]
     * thumbnails using Normalised Cross-Correlation over a ±[SEARCH_RADIUS]
     * pixel grid.  The returned offset is in full-resolution pixels.
     */
    private fun findOffset(ref: IntArray, tgt: IntArray, tw: Int, th: Int): Pair<Int, Int> {
        val refMean = ref.average()
        val tgtMean = tgt.average()
        var bestNcc = Double.NEGATIVE_INFINITY
        var bestDx = 0
        var bestDy = 0

        for (dy in -SEARCH_RADIUS..SEARCH_RADIUS) {
            for (dx in -SEARCH_RADIUS..SEARCH_RADIUS) {
                var num = 0.0
                var dr2 = 0.0
                var dt2 = 0.0
                for (ty in 0 until th) {
                    val sy = ty + dy
                    if (sy < 0 || sy >= th) continue
                    for (tx in 0 until tw) {
                        val sx = tx + dx
                        if (sx < 0 || sx >= tw) continue
                        val rv = ref[ty * tw + tx] - refMean
                        val tv = tgt[sy * tw + sx] - tgtMean
                        num += rv * tv
                        dr2 += rv * rv
                        dt2 += tv * tv
                    }
                }
                val ncc = if (dr2 > 0.0 && dt2 > 0.0) num / sqrt(dr2 * dt2) else 0.0
                if (ncc > bestNcc) {
                    bestNcc = ncc
                    bestDx = dx * THUMB_SCALE
                    bestDy = dy * THUMB_SCALE
                }
            }
        }
        return Pair(bestDx, bestDy)
    }

    // ── Demosaic & export ─────────────────────────────────────────────────────

    /**
     * Converts the accumulated max-stack to an ARGB_8888 [Bitmap] using simple
     * 2×2 Bayer block demosaicing (each output pixel covers one 2×2 sensor block).
     *
     * @param cfaPattern [android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT]
     * @param whiteLevel [android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL],
     *                   or ≤ 0 to auto-detect from captured pixel data.
     * @return the demosaiced [Bitmap], or `null` if no frames have been processed.
     */
    fun getResultBitmap(cfaPattern: Int, whiteLevel: Int): Bitmap? {
        val raw = maxStack ?: return null
        val w = frameWidth
        val h = frameHeight
        val wl = if (whiteLevel > 0) whiteLevel else maxPixelValue.coerceAtLeast(255)
        val scale = 255.0 / wl

        // Row/column offsets of R, Gr, Gb, B within a 2×2 Bayer block.
        // Patterns: 0=RGGB, 1=GRBG, 2=GBRG, 3=BGGR
        val rRow: Int; val rCol: Int
        val gRow1: Int; val gCol1: Int
        val gRow2: Int; val gCol2: Int
        val bRow: Int; val bCol: Int
        when (cfaPattern) {
            1 -> { rRow = 0; rCol = 1; gRow1 = 0; gCol1 = 0; gRow2 = 1; gCol2 = 1; bRow = 1; bCol = 0 }
            2 -> { rRow = 1; rCol = 0; gRow1 = 0; gCol1 = 0; gRow2 = 1; gCol2 = 1; bRow = 0; bCol = 1 }
            3 -> { rRow = 1; rCol = 1; gRow1 = 0; gCol1 = 1; gRow2 = 1; gCol2 = 0; bRow = 0; bCol = 0 }
            else -> { rRow = 0; rCol = 0; gRow1 = 0; gCol1 = 1; gRow2 = 1; gCol2 = 0; bRow = 1; bCol = 1 }
        }

        val bw = w / 2
        val bh = h / 2
        val bmpPixels = IntArray(bw * bh)

        for (by in 0 until bh) {
            val py = by * 2
            for (bx in 0 until bw) {
                val px = bx * 2
                val rVal  = raw[(py + rRow)  * w + (px + rCol)].toInt()  and 0xFFFF
                val gVal1 = raw[(py + gRow1) * w + (px + gCol1)].toInt() and 0xFFFF
                val gVal2 = raw[(py + gRow2) * w + (px + gCol2)].toInt() and 0xFFFF
                val bVal  = raw[(py + bRow)  * w + (px + bCol)].toInt()  and 0xFFFF

                val r = (rVal * scale).toInt().coerceIn(0, 255)
                val g = ((gVal1 + gVal2) / 2.0 * scale).toInt().coerceIn(0, 255)
                val b = (bVal * scale).toInt().coerceIn(0, 255)

                bmpPixels[by * bw + bx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        return Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888).also {
            it.setPixels(bmpPixels, 0, bw, 0, 0, bw, bh)
        }
    }
}
