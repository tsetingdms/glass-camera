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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Tuning for one multi-frame mode. */
class MergeParams(
    /** Sum of the R+G+B differences (0..765) still treated as the same detail; above it a frame is ignored there. */
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
        val NIGHT = MergeParams(robust = 54, targetMean = 0.40f, minGain = 1.0f, maxGain = 3.0f, saturation = 1.10f, sharpen = 0.35f)

        /** Frames taken ~1 EV darker (highlights kept), averaged, then shadows lifted. */
        val HDR = MergeParams(robust = 42, targetMean = 0.45f, minGain = 1.3f, maxGain = 2.4f, saturation = 1.06f, sharpen = 0.25f)

        /** Front camera: a few frames averaged to remove the small sensor's grain. */
        val CLEAN = MergeParams(robust = 42, targetMean = 0.42f, minGain = 1.0f, maxGain = 1.35f, saturation = 1.04f, sharpen = 0.22f)
    }
}

/**
 * A small HDR+-style pipeline for a budget phone:
 *  1. pick the sharpest frame as the reference,
 *  2. find each other frame's hand-shake offset (coarse-to-fine search on small grey copies),
 *  3. average the frames pixel by pixel, ignoring pixels that moved (no ghosts),
 *  4. local tone mapping: lift dark areas more than bright ones, then a little sharpening.
 * Works in horizontal strips decoded straight from the JPEGs, so memory stays at about one picture.
 */
object BurstMerge {
    private const val STRIP = 96
    private const val COARSE = 8
    private const val FINE = 2
    private const val CELL = 32

    private class Gray(val w: Int, val h: Int, val px: IntArray)

    suspend fun merge(jpegs: List<ByteArray>, p: MergeParams, progress: (Float) -> Unit): Bitmap {
        require(jpegs.isNotEmpty()) { "No frames" }

        val coarse = jpegs.map { decodeGray(it, COARSE) }
        val ref = coarse.indices.maxByOrNull { sharpness(coarse[it]) } ?: 0
        val (width, height) = bounds(jpegs[ref])

        // Hand-shake offsets, in full-resolution pixels: frame pixel (x + dx, y + dy) shows reference pixel (x, y).
        val shifts = arrayOfNulls<IntArray>(jpegs.size)
        shifts[ref] = intArrayOf(0, 0)
        val refFine = decodeGray(jpegs[ref], FINE)
        for (i in jpegs.indices) {
            if (i != ref && bounds(jpegs[i]) == width to height) {
                shifts[i] = align(coarse[ref], coarse[i], refFine, jpegs[i])
            }
            progress(0.15f * (i + 1) / jpegs.size)
        }

        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val decoders = jpegs.mapIndexed { i, jpeg -> if (shifts[i] != null) regionDecoder(jpeg) else null }
        try {
            val others = jpegs.indices.filter { it != ref && shifts[it] != null }
            val strips = (height + STRIP - 1) / STRIP
            val next = AtomicInteger(0)
            val done = AtomicInteger(0)
            val workers = max(1, min(4, Runtime.getRuntime().availableProcessors() - 1))
            coroutineScope {
                (0 until workers).map {
                    async(Dispatchers.Default) {
                        val buffers = StripBuffers(width * STRIP)
                        while (true) {
                            val s = next.getAndIncrement()
                            if (s >= strips) break
                            val y0 = s * STRIP
                            mergeStrip(y0, min(height, y0 + STRIP), width, height, ref, others, shifts, decoders, p, out, buffers)
                            progress(0.15f + 0.65f * done.incrementAndGet() / strips)
                        }
                    }
                }.awaitAll()
            }
        } finally {
            decoders.forEach { it?.recycle() }
        }

        tone(out, p) { progress(0.8f + 0.2f * it) }
        return out
    }

    // region Alignment

    private fun align(refCoarse: Gray, frameCoarse: Gray, refFine: Gray, jpeg: ByteArray): IntArray? {
        val c = search(refCoarse, frameCoarse, 0, 0, radius = 10, margin = refCoarse.w / 8, step = 2)
        val frameFine = decodeGray(jpeg, FINE)
        val k = COARSE / FINE
        val f = search(refFine, frameFine, c[0] * k, c[1] * k, radius = 4, margin = refFine.w / 4, step = 2)
        // Too different even at the best offset (moved a lot, or blurred): leave this frame out.
        if (f[2] > 22 * 1024) return null
        return intArrayOf(f[0] * FINE, f[1] * FINE)
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

    private class StripBuffers(size: Int) {
        val ref = IntArray(size)
        val frame = IntArray(size)
        val out = IntArray(size)
        val r = FloatArray(size)
        val g = FloatArray(size)
        val b = FloatArray(size)
        val w = FloatArray(size)
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

    private fun mergeStrip(
        y0: Int,
        y1: Int,
        width: Int,
        height: Int,
        ref: Int,
        others: List<Int>,
        shifts: Array<IntArray?>,
        decoders: List<BitmapRegionDecoder?>,
        p: MergeParams,
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
        for (i in 0 until n) {
            val c = refPx[i]
            sr[i] = (c shr 16 and 255).toFloat()
            sg[i] = (c shr 8 and 255).toFloat()
            sb[i] = (c and 255).toFloat()
            sw[i] = 1f
        }

        val t = p.robust
        for (k in others) {
            val dx = shifts[k]!![0]
            val dy = shifts[k]!![1]
            val fy0 = max(0, y0 + dy)
            val fy1 = min(height, y1 + dy)
            val fx0 = max(0, dx)
            val fx1 = min(width, width + dx)
            if (fy1 <= fy0 || fx1 <= fx0) continue
            val rw = fx1 - fx0
            val px = decodeRegion(decoders[k]!!, Rect(fx0, fy0, fx1, fy1), buf.frame)
            for (fy in fy0 until fy1) {
                val ry = fy - dy - y0
                if (ry < 0 || ry >= h) continue
                val src = (fy - fy0) * rw - fx0
                val dst = ry * width - dx
                for (fx in fx0 until fx1) {
                    val c = px[src + fx]
                    val i = dst + fx
                    val r0 = refPx[i]
                    val cr = c shr 16 and 255
                    val cg = c shr 8 and 255
                    val cb = c and 255
                    val d = abs(cr - (r0 shr 16 and 255)) + abs(cg - (r0 shr 8 and 255)) + abs(cb - (r0 and 255))
                    val weight = when {
                        d <= t -> 1f
                        d >= 2 * t -> 0f
                        else -> (2 * t - d).toFloat() / t
                    }
                    if (weight > 0f) {
                        sr[i] += cr * weight
                        sg[i] += cg * weight
                        sb[i] += cb * weight
                        sw[i] += weight
                    }
                }
            }
        }

        val o = buf.out
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
