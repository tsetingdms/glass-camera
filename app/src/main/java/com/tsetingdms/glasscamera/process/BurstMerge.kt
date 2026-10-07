package com.tsetingdms.glasscamera.process

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.Arrays
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Tuning for one multi-frame mode. */
data class MergeParams(
    /**
     * Smallest R+G+B difference of 3×3 patch averages (0..765) still treated as noise; above about twice the limit a
     * frame is ignored there. The real limit is measured from the frames and only ever raised above this.
     */
    val robust: Int,
    /** Average brightness (0..1) the picture is lifted towards. */
    val targetMean: Float,
    val minGain: Float,
    val maxGain: Float,
    val saturation: Float,
    val sharpen: Float,
) {
    companion object {
        /** Low light: many frames averaged, then brightened. */
        val NIGHT = MergeParams(robust = 20, targetMean = 0.45f, minGain = 1.0f, maxGain = 3.0f, saturation = 1.10f, sharpen = 0.6f)

        /** Frames taken ~1 EV darker (highlights kept), averaged, then shadows lifted. */
        val HDR = MergeParams(robust = 16, targetMean = 0.45f, minGain = 1.3f, maxGain = 2.4f, saturation = 1.06f, sharpen = 0.25f)

        /** Front camera: a few frames averaged to remove the small sensor's grain. */
        val CLEAN = MergeParams(robust = 16, targetMean = 0.42f, minGain = 1.0f, maxGain = 1.35f, saturation = 1.04f, sharpen = 0.22f)
    }
}

/** A merged picture and how many frames went into it (shaky or blurred ones are left out). */
class Merged(val bitmap: Bitmap, val frames: Int)

/**
 * A small HDR+-style pipeline for a budget phone:
 *  1. pick the sharpest frame as the reference,
 *  2. find each other frame's overall hand-shake offset (coarse-to-fine search on small grey copies), then refine it
 *     per 64-pixel tile to sub-pixel precision, so a slightly rotated hand or a small movement still lines up,
 *  3. measure how much the aligned frames differ from the reference at each brightness (their noise), then average
 *     them pixel by pixel, ignoring pixels that differ by more than noise (something moved: no ghosts),
 *  4. local tone mapping: lift dark areas more than bright ones, then a little sharpening.
 * Works in horizontal strips decoded straight from the JPEGs, so memory stays at about one picture.
 */
object BurstMerge {
    private const val STRIP = 96
    private const val COARSE = 8
    private const val FINE = 2
    private const val CELL = 32

    /** Alignment tile, in half-resolution pixels (64 full-resolution pixels). */
    private const val TILE = 32

    /** How far a tile may move away from its frame's overall offset, in half-resolution pixels. */
    private const val TILE_RADIUS = 3

    /** Full-resolution touch-up reach per strip and tile column, in pixels each way ([refine]). */
    private const val REFINE = 2

    /** Extra frame rows a strip may need: neighbouring tiles can be shifted differently, plus the touch-up reach. */
    private const val SLACK = 2 * (TILE_RADIUS + 1) * FINE + 2 * (REFINE + 3)

    /** Brightness bands with their own noise limit. */
    private const val BANDS = 8

    /** Floors sample positions with a cheap cast (valid for values above −1024). */
    private const val FLOOR = 1024f
    private const val BIAS = 1024

    private class Gray(val w: Int, val h: Int, val px: IntArray)

    /** Offsets per tile, in full-resolution pixels: frame pixel (x + dx, y + dy) shows reference pixel (x, y). */
    private class Motion(val cols: Int, val rows: Int, val dx: FloatArray, val dy: FloatArray)

    /** Which tile centres each picture column and row lies between (the same for every frame). */
    private class Grid(width: Int, height: Int, val cols: Int, val rows: Int) {
        val x0 = IntArray(width)
        val x1 = IntArray(width)
        val fx = FloatArray(width)
        val y0 = IntArray(height)
        val y1 = IntArray(height)
        val fy = FloatArray(height)

        init {
            val span = (TILE * FINE).toFloat()
            for (x in 0 until width) {
                val t = ((x + 0.5f) / span - 0.5f).coerceIn(0f, (cols - 1).toFloat())
                x0[x] = t.toInt()
                x1[x] = min(x0[x] + 1, cols - 1)
                fx[x] = t - x0[x]
            }
            for (y in 0 until height) {
                val t = ((y + 0.5f) / span - 0.5f).coerceIn(0f, (rows - 1).toFloat())
                y0[y] = t.toInt()
                y1[y] = min(y0[y] + 1, rows - 1)
                fy[y] = t - y0[y]
            }
        }
    }

    suspend fun merge(jpegs: List<ByteArray>, p: MergeParams, progress: (Float) -> Unit): Merged {
        require(jpegs.isNotEmpty()) { "No frames" }

        val coarse = jpegs.map { decodeGray(it, COARSE) }
        val ref = coarse.indices.maxByOrNull { sharpness(coarse[it]) } ?: 0
        val (width, height) = bounds(jpegs[ref])

        val refFine = decodeGray(jpegs[ref], FINE)
        val cols = max(1, refFine.w / TILE)
        val rows = max(1, refFine.h / TILE)
        val motions = arrayOfNulls<Motion>(jpegs.size)
        motions[ref] = Motion(cols, rows, FloatArray(cols * rows), FloatArray(cols * rows))
        for (i in jpegs.indices) {
            if (i != ref && bounds(jpegs[i]) == width to height) {
                motions[i] = align(coarse[ref], coarse[i], refFine, jpegs[i], cols, rows)
            }
            progress(0.2f * (i + 1) / jpegs.size)
        }

        val others = jpegs.indices.filter { it != ref && motions[it] != null }
        val grid = Grid(width, height, cols, rows)
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val decoders = jpegs.mapIndexed { i, jpeg -> if (motions[i] != null) regionDecoder(jpeg) else null }
        try {
            val limits = noiseLimits(width, height, ref, others, motions, decoders, grid, p)
            progress(0.25f)
            val strips = (height + STRIP - 1) / STRIP
            val next = AtomicInteger(0)
            val done = AtomicInteger(0)
            coroutineScope {
                (0 until workers()).map {
                    async(Dispatchers.Default) {
                        val buffers = StripBuffers(width, cols)
                        while (true) {
                            val s = next.getAndIncrement()
                            if (s >= strips) break
                            val y0 = s * STRIP
                            mergeStrip(y0, min(height, y0 + STRIP), width, height, ref, others, motions, decoders, grid, limits, out, buffers)
                            progress(0.25f + 0.55f * done.incrementAndGet() / strips)
                        }
                    }
                }.awaitAll()
            }
        } finally {
            decoders.forEach { it?.recycle() }
        }

        tone(out, p) { progress(0.8f + 0.2f * it) }
        return Merged(out, 1 + others.size)
    }

    private fun workers() = max(1, min(4, Runtime.getRuntime().availableProcessors() - 1))

    // region Alignment

    private suspend fun align(refCoarse: Gray, frameCoarse: Gray, refFine: Gray, jpeg: ByteArray, cols: Int, rows: Int): Motion? {
        val c = search(refCoarse, frameCoarse, 0, 0, radius = 10, margin = refCoarse.w / 8, step = 2)
        val frameFine = decodeGray(jpeg, FINE)
        if (frameFine.w != refFine.w || frameFine.h != refFine.h) return null
        val k = COARSE / FINE
        val f = search(refFine, frameFine, c[0] * k, c[1] * k, radius = 4, margin = refFine.w / 4, step = 2)
        // Too different even at the best offset (moved a lot, or blurred): leave this frame out.
        if (f[2] > 22 * 1024) return null
        return tiles(refFine, frameFine, f[0], f[1], cols, rows)
    }

    /** Best offset (dx, dy) of [b] against [a] around (gx, gy), plus its mean difference × 1024. */
    private fun search(a: Gray, b: Gray, gx: Int, gy: Int, radius: Int, margin: Int, step: Int): IntArray {
        var best = Long.MAX_VALUE
        var bx = gx
        var by = gy
        for (dy in gy - radius..gy + radius) {
            for (dx in gx - radius..gx + radius) {
                var sum = 0L
                var n = 0
                var y = margin
                while (y < a.h - margin) {
                    val yy = y + dy
                    if (yy >= 0 && yy < b.h) {
                        val ar = y * a.w
                        val br = yy * b.w
                        var x = margin
                        while (x < a.w - margin) {
                            val xx = x + dx
                            if (xx >= 0 && xx < b.w) {
                                sum += abs(a.px[ar + x] - b.px[br + xx])
                                n++
                            }
                            x += step
                        }
                    }
                    y += step
                }
                if (n > 64) {
                    val score = sum * 1024 / n
                    if (score < best) {
                        best = score
                        bx = dx
                        by = dy
                    }
                }
            }
        }
        return intArrayOf(bx, by, if (best == Long.MAX_VALUE) Int.MAX_VALUE else best.toInt())
    }

    /**
     * Refines the frame's overall offset (gx, gy) for every tile (in parallel), then a 3×3 median replaces tiles
     * that disagree with their neighbours. Tiles without a clear match keep the overall offset.
     */
    private suspend fun tiles(a: Gray, b: Gray, gx: Int, gy: Int, cols: Int, rows: Int): Motion {
        val dx = FloatArray(cols * rows) { gx.toFloat() }
        val dy = FloatArray(cols * rows) { gy.toFloat() }
        val workers = workers()
        coroutineScope {
            (0 until workers).map { w ->
                async(Dispatchers.Default) {
                    val side = 2 * TILE_RADIUS + 1
                    val sad = LongArray(side * side)
                    val found = FloatArray(2)
                    var ty = w
                    while (ty < rows) {
                        for (tx in 0 until cols) {
                            if (refineTile(a, b, tx * TILE, ty * TILE, gx, gy, sad, found)) {
                                dx[ty * cols + tx] = found[0]
                                dy[ty * cols + tx] = found[1]
                            }
                        }
                        ty += workers
                    }
                }
            }.awaitAll()
        }
        val mx = median3x3(dx, cols, rows)
        val my = median3x3(dy, cols, rows)
        for (i in mx.indices) {
            mx[i] *= FINE
            my[i] *= FINE
        }
        return Motion(cols, rows, mx, my)
    }

    /**
     * Searches ±[TILE_RADIUS] around (gx, gy) for the tile at (x0, y0) and writes the best offset, refined to
     * sub-pixel precision with a parabola through its neighbours, into [result]. Returns false when there's no clear
     * answer: plain areas (sky, walls) match everywhere about equally, and a best match on the edge of the search
     * means the tile moved further than allowed.
     */
    private fun refineTile(a: Gray, b: Gray, x0: Int, y0: Int, gx: Int, gy: Int, sad: LongArray, result: FloatArray): Boolean {
        val r = TILE_RADIUS
        val side = 2 * r + 1
        var best = Long.MAX_VALUE
        var bi = 0
        var total = 0L
        for (j in 0 until side) {
            val oy = gy + j - r
            for (i in 0 until side) {
                val ox = gx + i - r
                var sum = 0L
                var count = 0
                var y = y0
                while (y < y0 + TILE) {
                    val yy = y + oy
                    if (yy >= 0 && yy < b.h) {
                        val ar = y * a.w
                        val br = yy * b.w + ox
                        var x = x0
                        while (x < x0 + TILE) {
                            val xx = x + ox
                            if (xx >= 0 && xx < b.w) {
                                sum += abs(a.px[ar + x] - b.px[br + x])
                                count++
                            }
                            x += 2
                        }
                    }
                    y += 2
                }
                // Mostly outside the frame (tile near the picture edge): no reliable answer.
                if (count < 128) return false
                val score = sum * 256 / count
                sad[j * side + i] = score
                total += score
                if (score < best) {
                    best = score
                    bi = j * side + i
                }
            }
        }
        val bx = bi % side
        val by = bi / side
        if (bx == 0 || by == 0 || bx == side - 1 || by == side - 1) return false
        if (best * 4 > total / (side * side) * 3) return false
        result[0] = gx + bx - r + vertex(sad[bi - 1], best, sad[bi + 1])
        result[1] = gy + by - r + vertex(sad[bi - side], best, sad[bi + side])
        return true
    }

    /** Position (−0.5..0.5) of the lowest point of a parabola through three equally spaced scores. */
    private fun vertex(left: Long, mid: Long, right: Long): Float {
        val den = left - 2 * mid + right
        if (den <= 0) return 0f
        return (0.5f * (left - right) / den).coerceIn(-0.5f, 0.5f)
    }

    private fun median3x3(v: FloatArray, cols: Int, rows: Int): FloatArray {
        val out = FloatArray(v.size)
        val window = FloatArray(9)
        for (y in 0 until rows) {
            for (x in 0 until cols) {
                var n = 0
                for (j in max(0, y - 1)..min(rows - 1, y + 1)) {
                    for (i in max(0, x - 1)..min(cols - 1, x + 1)) window[n++] = v[j * cols + i]
                }
                window.sort(0, n)
                out[y * cols + x] = if (n % 2 == 1) window[n / 2] else 0.5f * (window[n / 2 - 1] + window[n / 2])
            }
        }
        return out
    }

    private fun sharpness(g: Gray): Long {
        var s = 0L
        for (y in 0 until g.h - 1 step 2) {
            val row = y * g.w
            for (x in 0 until g.w - 1 step 2) {
                val v = g.px[row + x]
                s += abs(g.px[row + x + 1] - v) + abs(g.px[row + g.w + x] - v)
            }
        }
        return s
    }

    private fun decodeGray(jpeg: ByteArray, sample: Int): Gray {
        val o = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, o) ?: error("Couldn't decode a frame")
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        bmp.recycle()
        for (i in px.indices) {
            val c = px[i]
            px[i] = ((c shr 16 and 255) * 77 + (c shr 8 and 255) * 150 + (c and 255) * 29) shr 8
        }
        return Gray(w, h, px)
    }

    private fun bounds(jpeg: ByteArray): Pair<Int, Int> {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, o)
        return o.outWidth to o.outHeight
    }

    // endregion

    // region Merge

    private class StripBuffers(width: Int, cols: Int) {
        val frameRows = STRIP + SLACK
        val ref = IntArray(width * STRIP)
        val frame = IntArray(width * frameRows)
        val aligned = IntArray(width * STRIP)
        val limit = IntArray(width * STRIP)
        val dr = IntArray(width * STRIP)
        val dg = IntArray(width * STRIP)
        val db = IntArray(width * STRIP)
        val tmp = IntArray(width * STRIP)
        val diff = IntArray(width * STRIP)
        val r = FloatArray(width * STRIP)
        val g = FloatArray(width * STRIP)
        val b = FloatArray(width * STRIP)
        val w = FloatArray(width * STRIP)
        val weight = FloatArray(width * STRIP)
        val weightTmp = FloatArray(width * STRIP)
        val rowDx = FloatArray(cols)
        val rowDy = FloatArray(cols)
        val refLuma = IntArray(width * STRIP)
        val frameLuma = IntArray(width * frameRows)
        val rawX = FloatArray(cols)
        val rawY = FloatArray(cols)
        val corrX = FloatArray(cols)
        val corrY = FloatArray(cols)
        val sad = LongArray((2 * REFINE + 1) * (2 * REFINE + 1))
    }

    @Suppress("DEPRECATION")
    private fun regionDecoder(jpeg: ByteArray): BitmapRegionDecoder {
        val decoder = if (Build.VERSION.SDK_INT >= 31) {
            BitmapRegionDecoder.newInstance(jpeg, 0, jpeg.size)
        } else {
            BitmapRegionDecoder.newInstance(jpeg, 0, jpeg.size, false)
        }
        return requireNotNull(decoder) { "Couldn't open a frame" }
    }

    private fun decodeRegion(decoder: BitmapRegionDecoder, r: Rect, into: IntArray): IntArray {
        val o = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bmp = decoder.decodeRegion(r, o) ?: error("Couldn't decode a frame region")
        val w = min(bmp.width, r.width())
        val h = min(bmp.height, r.height())
        bmp.getPixels(into, 0, r.width(), 0, 0, w, h)
        bmp.recycle()
        return into
    }

    private fun luma(c: Int) = ((c shr 16 and 255) * 77 + (c shr 8 and 255) * 150 + (c and 255) * 29) shr 8

    private fun band(c: Int) = luma(c) * BANDS shr 8

    /**
     * Fills [into] with the frame's pixels that line up with reference rows [y0] until [y1], or 0 where the frame
     * has none (decoded pixels are opaque, so never 0). Offsets are interpolated between tile centres, so the frame
     * is gently warped rather than cut into blocks, touched up at full resolution for this strip ([refine]), and
     * sampled between pixels (bilinear): rounding to whole pixels left edges up to half a pixel apart, which the
     * merge then had to reject (ragged edges).
     */
    private fun alignStrip(
        y0: Int,
        y1: Int,
        width: Int,
        height: Int,
        m: Motion,
        grid: Grid,
        decoder: BitmapRegionDecoder,
        refLuma: IntArray,
        buf: StripBuffers,
        into: IntArray,
    ) {
        Arrays.fill(into, 0, (y1 - y0) * width, 0)
        val cols = m.cols
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for (k in grid.y0[y0] * cols until (grid.y1[y1 - 1] + 1) * cols) {
            lo = min(lo, m.dy[k])
            hi = max(hi, m.dy[k])
        }
        val fy0 = max(0, y0 + floor(lo).toInt() - REFINE - 2)
        val fy1 = min(height, min(y1 + ceil(hi).toInt() + REFINE + 2, fy0 + buf.frameRows))
        if (fy1 <= fy0) return
        val px = decodeRegion(decoder, Rect(0, fy0, width, fy1), buf.frame)
        val frameLuma = buf.frameLuma
        for (i in 0 until (fy1 - fy0) * width) frameLuma[i] = luma(px[i])
        refine(y0, y1, width, fy0, fy1, m, grid, refLuma, frameLuma, buf)
        val corrX = buf.corrX
        val corrY = buf.corrY
        val rowDx = buf.rowDx
        val rowDy = buf.rowDy
        for (y in y0 until y1) {
            val ta = grid.y0[y] * cols
            val tb = grid.y1[y] * cols
            val wy = grid.fy[y]
            for (c in 0 until cols) {
                rowDx[c] = m.dx[ta + c] + (m.dx[tb + c] - m.dx[ta + c]) * wy + corrX[c]
                rowDy[c] = m.dy[ta + c] + (m.dy[tb + c] - m.dy[ta + c]) * wy + corrY[c]
            }
            val base = (y - y0) * width
            for (x in 0 until width) {
                val ca = grid.x0[x]
                val cb = grid.x1[x]
                val wx = grid.fx[x]
                val sx = x + rowDx[ca] + (rowDx[cb] - rowDx[ca]) * wx
                val sy = y + rowDy[ca] + (rowDy[cb] - rowDy[ca]) * wx
                val ix = (sx + FLOOR).toInt() - BIAS
                val iy = (sy + FLOOR).toInt() - BIAS
                if (ix < 0 || ix + 1 >= width || iy < fy0 || iy + 1 >= fy1) continue
                val ax = sx - ix
                val ay = sy - iy
                val i00 = (iy - fy0) * width + ix
                val p00 = px[i00]
                val p10 = px[i00 + 1]
                val p01 = px[i00 + width]
                val p11 = px[i00 + width + 1]
                val w11 = ax * ay
                val w10 = ax - w11
                val w01 = ay - w11
                val w00 = 1f - ax - ay + w11
                val r = (p00 shr 16 and 255) * w00 + (p10 shr 16 and 255) * w10 + (p01 shr 16 and 255) * w01 + (p11 shr 16 and 255) * w11
                val g = (p00 shr 8 and 255) * w00 + (p10 shr 8 and 255) * w10 + (p01 shr 8 and 255) * w01 + (p11 shr 8 and 255) * w11
                val b = (p00 and 255) * w00 + (p10 and 255) * w10 + (p01 and 255) * w01 + (p11 and 255) * w11
                into[base + x] = (0xFF shl 24) or ((r + 0.5f).toInt() shl 16) or ((g + 0.5f).toInt() shl 8) or (b + 0.5f).toInt()
            }
        }
    }

    /**
     * Full-resolution touch-up of the tile offsets for one strip. The half-resolution tile search can be about half a
     * pixel off, which softened edges once 16 frames were averaged (or got them rejected: dotted outlines). Each tile
     * column's block (64 px wide, the strip's height) is matched against the reference at whole-pixel steps around its
     * current offset (following the best match up to [REFINE] px), then a parabola gives the sub-pixel position. Plain
     * blocks keep their offset. The corrections (pixels, added to the tile offsets) go to [StripBuffers.corrX] /
     * [StripBuffers.corrY] after a 3-wide median across columns.
     */
    private fun refine(
        y0: Int,
        y1: Int,
        width: Int,
        fy0: Int,
        fy1: Int,
        m: Motion,
        grid: Grid,
        refLuma: IntArray,
        frameLuma: IntArray,
        buf: StripBuffers,
    ) {
        val cols = m.cols
        val ym = (y0 + y1 - 1) / 2
        val ta = grid.y0[ym] * cols
        val tb = grid.y1[ym] * cols
        val wy = grid.fy[ym]
        val span = TILE * FINE
        val side = 2 * REFINE + 1
        val sad = buf.sad
        val rawX = buf.rawX
        val rawY = buf.rawY
        for (c in 0 until cols) {
            rawX[c] = 0f
            rawY[c] = 0f
            val baseX = m.dx[ta + c] + (m.dx[tb + c] - m.dx[ta + c]) * wy
            val baseY = m.dy[ta + c] + (m.dy[tb + c] - m.dy[ta + c]) * wy
            val ix = (baseX + FLOOR + 0.5f).toInt() - BIAS
            val iy = (baseY + FLOOR + 0.5f).toInt() - BIAS
            val bx0 = c * span
            val bx1 = if (c == cols - 1) width else min(width, bx0 + span)
            Arrays.fill(sad, -1L)

            fun score(ox: Int, oy: Int): Long {
                if (ox < -REFINE || ox > REFINE || oy < -REFINE || oy > REFINE) return Long.MAX_VALUE
                val k = (oy + REFINE) * side + (ox + REFINE)
                if (sad[k] < 0) sad[k] = blockSad(refLuma, frameLuma, width, y0, y1, fy0, fy1, bx0, bx1, ix + ox, iy + oy)
                return sad[k]
            }

            var cx = 0
            var cy = 0
            for (step in 0..REFINE) {
                var best = Long.MAX_VALUE
                var bx = cx
                var by = cy
                for (oy in cy - 1..cy + 1) {
                    for (ox in cx - 1..cx + 1) {
                        val v = score(ox, oy)
                        if (v < best) {
                            best = v
                            bx = ox
                            by = oy
                        }
                    }
                }
                if (best == Long.MAX_VALUE || (bx == cx && by == cy)) break
                cx = bx
                cy = by
            }
            val mid = score(cx, cy)
            if (mid == Long.MAX_VALUE) continue
            val left = score(cx - 1, cy)
            val right = score(cx + 1, cy)
            val up = score(cx, cy - 1)
            val down = score(cx, cy + 1)
            if (left == Long.MAX_VALUE || right == Long.MAX_VALUE || up == Long.MAX_VALUE || down == Long.MAX_VALUE) continue
            // Plain block (every nearby offset matches about as well): keep the tile offset.
            if (mid * 10 > (left + right + up + down) / 4 * 9) continue
            rawX[c] = ix + cx + vertex(left, mid, right) - baseX
            rawY[c] = iy + cy + vertex(up, mid, down) - baseY
        }
        val corrX = buf.corrX
        val corrY = buf.corrY
        for (c in 0 until cols) {
            val a = max(0, c - 1)
            val b = min(cols - 1, c + 1)
            corrX[c] = median3(rawX[a], rawX[c], rawX[b])
            corrY[c] = median3(rawY[a], rawY[c], rawY[b])
        }
    }

    private fun median3(a: Float, b: Float, c: Float) = max(min(a, b), min(max(a, b), c))

    /** Mean luma difference (× 1024) between a reference block and the frame shifted by (dx, dy); MAX if it barely overlaps. */
    private fun blockSad(
        refLuma: IntArray,
        frameLuma: IntArray,
        width: Int,
        y0: Int,
        y1: Int,
        fy0: Int,
        fy1: Int,
        bx0: Int,
        bx1: Int,
        dx: Int,
        dy: Int,
    ): Long {
        var sum = 0L
        var n = 0
        var y = y0
        while (y < y1) {
            val fy = y + dy
            if (fy >= fy0 && fy < fy1) {
                val rr = (y - y0) * width
                val fr = (fy - fy0) * width + dx
                var x = bx0
                while (x < bx1) {
                    val fx = x + dx
                    if (fx >= 0 && fx < width) {
                        sum += abs(refLuma[rr + x] - frameLuma[fr + x])
                        n++
                    }
                    x += 2
                }
            }
            y += 3
        }
        return if (n >= 256) sum * 1024 / n else Long.MAX_VALUE
    }

    /**
     * For each strip pixel, how much the aligned frame differs from the reference once both are averaged over 3×3
     * pixels (sum of the R, G and B differences, 0..765). Averaging cancels most of the grain but not a real
     * difference, such as an edge that doesn't quite line up or something that moved, so even very grainy frames
     * (camera smoothing off) can be told apart from misalignment.
     */
    private fun patchDiff(ref: IntArray, frame: IntArray, width: Int, h: Int, buf: StripBuffers): IntArray {
        val n = width * h
        val dr = buf.dr
        val dg = buf.dg
        val db = buf.db
        for (i in 0 until n) {
            val c = frame[i]
            if (c == 0) {
                dr[i] = 0
                dg[i] = 0
                db[i] = 0
            } else {
                val r0 = ref[i]
                dr[i] = (c shr 16 and 255) - (r0 shr 16 and 255)
                dg[i] = (c shr 8 and 255) - (r0 shr 8 and 255)
                db[i] = (c and 255) - (r0 and 255)
            }
        }
        box3(dr, width, h, buf.tmp)
        box3(dg, width, h, buf.tmp)
        box3(db, width, h, buf.tmp)
        val d = buf.diff
        for (i in 0 until n) d[i] = (abs(dr[i]) + abs(dg[i]) + abs(db[i])) / 9
        return d
    }

    /** Two in-place 3×3 box averages (a 5×5 tent) over a strip, the outermost pixels repeating at the edges. */
    private fun soften(a: FloatArray, width: Int, h: Int, tmp: FloatArray) {
        repeat(2) {
            for (y in 0 until h) {
                val row = y * width
                for (x in 0 until width) {
                    val l = if (x > 0) a[row + x - 1] else a[row + x]
                    val r = if (x < width - 1) a[row + x + 1] else a[row + x]
                    tmp[row + x] = (l + a[row + x] + r) * (1f / 3f)
                }
            }
            for (y in 0 until h) {
                val row = y * width
                val up = if (y > 0) row - width else row
                val down = if (y < h - 1) row + width else row
                for (x in 0 until width) a[row + x] = (tmp[up + x] + tmp[row + x] + tmp[down + x]) * (1f / 3f)
            }
        }
    }

    /** In-place 3×3 box sum over a strip (the outermost pixels repeat at the edges). */
    private fun box3(a: IntArray, width: Int, h: Int, tmp: IntArray) {
        for (y in 0 until h) {
            val row = y * width
            for (x in 0 until width) {
                val l = if (x > 0) a[row + x - 1] else a[row + x]
                val r = if (x < width - 1) a[row + x + 1] else a[row + x]
                tmp[row + x] = l + a[row + x] + r
            }
        }
        for (y in 0 until h) {
            val row = y * width
            val up = if (y > 0) row - width else row
            val down = if (y < h - 1) row + width else row
            for (x in 0 until width) a[row + x] = tmp[up + x] + tmp[row + x] + tmp[down + x]
        }
    }

    /**
     * How different an aligned patch may be and still count as noise, per brightness band: 1.8 × the median patch
     * difference between the reference and up to two other frames over six sample strips. Frames without the
     * camera's own smoothing are grainier, so the limit rises with them; it never drops below the mode's value.
     */
    private fun noiseLimits(
        width: Int,
        height: Int,
        ref: Int,
        others: List<Int>,
        motions: Array<Motion?>,
        decoders: List<BitmapRegionDecoder?>,
        grid: Grid,
        p: MergeParams,
    ): IntArray {
        val fallback = IntArray(BANDS) { p.robust }
        if (others.isEmpty()) return fallback
        val hist = Array(BANDS) { IntArray(766) }
        val buf = StripBuffers(width, grid.cols)
        val sampleRows = 32
        val samples = 6
        for (s in 0 until samples) {
            val y0 = ((height - sampleRows) * (s + 0.5f) / samples).toInt().coerceIn(0, max(0, height - sampleRows))
            val y1 = min(height, y0 + sampleRows)
            if (y1 <= y0) continue
            val refPx = decodeRegion(decoders[ref]!!, Rect(0, y0, width, y1), buf.ref)
            for (i in 0 until width * (y1 - y0)) buf.refLuma[i] = luma(refPx[i])
            for (k in others.take(2)) {
                alignStrip(y0, y1, width, height, motions[k]!!, grid, decoders[k]!!, buf.refLuma, buf, buf.aligned)
                val diff = patchDiff(refPx, buf.aligned, width, y1 - y0, buf)
                for (i in 0 until width * (y1 - y0)) {
                    if (buf.aligned[i] != 0) hist[band(refPx[i])][diff[i]]++
                }
            }
        }
        val all = IntArray(766)
        for (h in hist) for (d in all.indices) all[d] += h[d]
        val overall = median(all) ?: return fallback
        return IntArray(BANDS) { b ->
            val med = if (hist[b].sum() >= 4000) median(hist[b]) ?: overall else overall
            max(p.robust, min(60, (med * 1.8f).roundToInt()))
        }
    }

    private fun median(hist: IntArray): Int? {
        val total = hist.sum()
        if (total == 0) return null
        var acc = 0
        for (d in hist.indices) {
            acc += hist[d]
            if (acc * 2 >= total) return d
        }
        return hist.size - 1
    }

    private fun mergeStrip(
        y0: Int,
        y1: Int,
        width: Int,
        height: Int,
        ref: Int,
        others: List<Int>,
        motions: Array<Motion?>,
        decoders: List<BitmapRegionDecoder?>,
        grid: Grid,
        limits: IntArray,
        out: Bitmap,
        buf: StripBuffers,
    ) {
        val h = y1 - y0
        val n = width * h
        val refPx = decodeRegion(decoders[ref]!!, Rect(0, y0, width, y1), buf.ref)
        val sr = buf.r
        val sg = buf.g
        val sb = buf.b
        val sw = buf.w
        val limit = buf.limit
        for (i in 0 until n) {
            val c = refPx[i]
            sr[i] = (c shr 16 and 255).toFloat()
            sg[i] = (c shr 8 and 255).toFloat()
            sb[i] = (c and 255).toFloat()
            sw[i] = 1f
            limit[i] = limits[band(c)]
            buf.refLuma[i] = luma(c)
        }

        val frame = buf.aligned
        val weights = buf.weight
        for (k in others) {
            alignStrip(y0, y1, width, height, motions[k]!!, grid, decoders[k]!!, buf.refLuma, buf, frame)
            val diff = patchDiff(refPx, frame, width, h, buf)
            for (i in 0 until n) {
                val d = diff[i]
                val t = limit[i]
                weights[i] = when {
                    frame[i] == 0 || d >= 2 * t -> 0f
                    d <= t -> 1f
                    else -> (2 * t - d).toFloat() / t
                }
            }
            // Spread each decision over its neighbours, so pixels along an edge don't flip one by one between
            // "averaged" and "reference only" (that left speckled, ragged outlines).
            soften(weights, width, h, buf.weightTmp)
            for (i in 0 until n) {
                val c = frame[i]
                val weight = weights[i]
                if (c != 0 && weight > 0.01f) {
                    sr[i] += (c shr 16 and 255) * weight
                    sg[i] += (c shr 8 and 255) * weight
                    sb[i] += (c and 255) * weight
                    sw[i] += weight
                }
            }
        }

        // The reference pixels aren't needed any more: reuse their buffer for the result.
        val o = buf.ref
        for (i in 0 until n) {
            val w = sw[i]
            val r = (sr[i] / w).roundToInt().coerceIn(0, 255)
            val g = (sg[i] / w).roundToInt().coerceIn(0, 255)
            val b = (sb[i] / w).roundToInt().coerceIn(0, 255)
            o[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        synchronized(out) { out.setPixels(o, 0, width, 0, y0, width, h) }
    }

    // endregion

    // region Tone mapping

    /**
     * Local tone mapping: a small, blurred brightness map decides how much each area is lifted
     * (dark areas a lot, bright ones barely), which keeps local contrast. Applied with a light
     * unsharp mask and a small saturation boost, in place.
     */
    private fun tone(out: Bitmap, p: MergeParams, progress: (Float) -> Unit) {
        val w = out.width
        val h = out.height
        val gw = max(1, (w + CELL - 1) / CELL)
        val gh = max(1, (h + CELL - 1) / CELL)
        val small = Bitmap.createScaledBitmap(out, gw, gh, true)
        val cells = IntArray(gw * gh)
        small.getPixels(cells, 0, gw, 0, 0, gw, gh)
        if (small !== out) small.recycle()
        val lum = FloatArray(gw * gh) {
            val c = cells[it]
            ((c shr 16 and 255) * 0.299f + (c shr 8 and 255) * 0.587f + (c and 255) * 0.114f) / 255f
        }
        Blur.box(lum, gw, gh, 2)
        Blur.box(lum, gw, gh, 2)
        val mean = lum.average().toFloat()
        val gain = (p.targetMean / max(mean, 0.02f)).coerceIn(p.minGain, p.maxGain)
        val map = FloatArray(gw * gh) {
            val l = max(lum[it], 0.004f)
            (l * gain / (1f + l * (gain - 1f))) / l
        }

        val x0 = IntArray(w)
        val x1 = IntArray(w)
        val fx = FloatArray(w)
        for (x in 0 until w) {
            val gx = ((x + 0.5f) / CELL - 0.5f).coerceIn(0f, (gw - 1).toFloat())
            x0[x] = gx.toInt()
            x1[x] = min(x0[x] + 1, gw - 1)
            fx[x] = gx - x0[x]
        }

        val rows = IntArray(w * STRIP)
        val result = IntArray(w * STRIP)
        val above = IntArray(w)
        val below = IntArray(w)
        var hasAbove = false
        val sharpen = p.sharpen
        val sat = p.saturation

        var y0 = 0
        while (y0 < h) {
            val y1 = min(h, y0 + STRIP)
            val sh = y1 - y0
            out.getPixels(rows, 0, w, 0, y0, w, sh)
            val hasBelow = y1 < h
            if (hasBelow) out.getPixels(below, 0, w, 0, y1, w, 1)

            for (ry in 0 until sh) {
                val y = y0 + ry
                val gy = ((y + 0.5f) / CELL - 0.5f).coerceIn(0f, (gh - 1).toFloat())
                val j0 = gy.toInt()
                val j1 = min(j0 + 1, gh - 1)
                val fy = gy - j0
                val base = ry * w
                for (x in 0 until w) {
                    val c = rows[base + x]
                    val up = if (ry > 0) rows[base - w + x] else if (hasAbove) above[x] else c
                    val down = if (ry < sh - 1) rows[base + w + x] else if (hasBelow) below[x] else c
                    val left = if (x > 0) rows[base + x - 1] else c
                    val right = if (x < w - 1) rows[base + x + 1] else c

                    val a = map[j0 * gw + x0[x]]
                    val b = map[j0 * gw + x1[x]]
                    val cc = map[j1 * gw + x0[x]]
                    val d = map[j1 * gw + x1[x]]
                    val top = a + (b - a) * fx[x]
                    val k = top + (cc + (d - cc) * fx[x] - top) * fy

                    var r = (c shr 16 and 255).toFloat()
                    var g = (c shr 8 and 255).toFloat()
                    var bl = (c and 255).toFloat()
                    val nr = ((up shr 16 and 255) + (down shr 16 and 255) + (left shr 16 and 255) + (right shr 16 and 255)) * 0.25f
                    val ng = ((up shr 8 and 255) + (down shr 8 and 255) + (left shr 8 and 255) + (right shr 8 and 255)) * 0.25f
                    val nb = ((up and 255) + (down and 255) + (left and 255) + (right and 255)) * 0.25f
                    r = (r + sharpen * (r - nr)) * k
                    g = (g + sharpen * (g - ng)) * k
                    bl = (bl + sharpen * (bl - nb)) * k
                    val luma = 0.299f * r + 0.587f * g + 0.114f * bl
                    r = luma + (r - luma) * sat
                    g = luma + (g - luma) * sat
                    bl = luma + (bl - luma) * sat

                    result[base + x] = (0xFF shl 24) or
                        (r.roundToInt().coerceIn(0, 255) shl 16) or
                        (g.roundToInt().coerceIn(0, 255) shl 8) or
                        bl.roundToInt().coerceIn(0, 255)
                }
            }
            // The last un-toned row is the next strip's "above" neighbour.
            System.arraycopy(rows, (sh - 1) * w, above, 0, w)
            hasAbove = true
            out.setPixels(result, 0, w, 0, y0, w, sh)
            progress(y1.toFloat() / h)
            y0 = y1
        }
    }

    // endregion
}

/** Small separable box blur for low-resolution float maps (edges clamped). */
object Blur {
    fun box(data: FloatArray, w: Int, h: Int, radius: Int) {
        if (radius <= 0) return
        val tmp = FloatArray(data.size)
        val div = 1f / (2 * radius + 1)
        for (y in 0 until h) {
            val row = y * w
            var sum = 0f
            for (i in -radius..radius) sum += data[row + i.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                tmp[row + x] = sum * div
                sum += data[row + (x + radius + 1).coerceAtMost(w - 1)] - data[row + (x - radius).coerceAtLeast(0)]
            }
        }
        for (x in 0 until w) {
            var sum = 0f
            for (i in -radius..radius) sum += tmp[i.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                data[y * w + x] = sum * div
                sum += tmp[(y + radius + 1).coerceAtMost(h - 1) * w + x] - tmp[(y - radius).coerceAtLeast(0) * w + x]
            }
        }
    }
}
