package com.tsetingdms.glasscamera.process

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Portrait mode without a depth sensor: ML Kit finds the person (on the phone, offline), the
 * background is blurred at 1/8 size (cheap, and a big blur looks like real bokeh), and the two are
 * blended along a feathered edge. The blur ignores the person's own pixels so their colours don't
 * bleed into the background as a halo.
 */
object Portrait {
    private const val MAX_SIDE = 4096
    private const val MASK_SIDE = 512
    private const val BG_SCALE = 8

    private val segmenter by lazy {
        Segmentation.getClient(
            SelfieSegmenterOptions.Builder()
                .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
                .build()
        )
    }

    private class Mask(val w: Int, val h: Int, val v: FloatArray)

    /** The photo with its background blurred, or null when no person was found. [rotation] makes the JPEG upright. */
    suspend fun process(jpeg: ByteArray, rotation: Int, progress: (Float) -> Unit): Bitmap? {
        val full = withContext(Dispatchers.Default) { decodeMutable(jpeg) }
        val w = full.width
        val h = full.height
        val rot = ((rotation % 360) + 360) % 360

        // The segmenter wants a small, upright picture.
        val scale = MASK_SIDE.toFloat() / max(w, h)
        val small = withContext(Dispatchers.Default) {
            val m = Matrix().apply {
                postScale(scale, scale)
                postRotate(rot.toFloat())
            }
            Bitmap.createBitmap(full, 0, 0, w, h, m, true)
        }
        val upright = try {
            segment(small)
        } finally {
            small.recycle()
        }
        progress(0.3f)

        return withContext(Dispatchers.Default) {
            // Mask in the photo's stored orientation, at small size.
            val sw = max(1, (w * scale).roundToInt())
            val sh = max(1, (h * scale).roundToInt())
            val uw = if (rot == 90 || rot == 270) sh else sw
            val uh = if (rot == 90 || rot == 270) sw else sh
            val mask = FloatArray(sw * sh)
            var person = 0
            for (j in 0 until sh) {
                for (i in 0 until sw) {
                    val u: Int
                    val v: Int
                    when (rot) {
                        90 -> { u = sh - 1 - j; v = i }
                        180 -> { u = sw - 1 - i; v = sh - 1 - j }
                        270 -> { u = j; v = sw - 1 - i }
                        else -> { u = i; v = j }
                    }
                    val mu = (u * upright.w / uw).coerceIn(0, upright.w - 1)
                    val mv = (v * upright.h / uh).coerceIn(0, upright.h - 1)
                    val value = upright.v[mv * upright.w + mu]
                    mask[j * sw + i] = value
                    if (value > 0.5f) person++
                }
            }
            if (person < sw * sh * 0.03f) {
                full.recycle()
                return@withContext null
            }
            // Soft, slightly tightened edge.
            Blur.box(mask, sw, sh, 1)
            Blur.box(mask, sw, sh, 1)
            for (i in mask.indices) mask[i] = smoothstep(0.3f, 0.7f, mask[i])
            progress(0.45f)

            val bg = blurredBackground(full, mask, sw, sh)
            progress(0.7f)
            composite(full, mask, sw, sh, bg, progress)
            full
        }
    }

    private suspend fun segment(bitmap: Bitmap): Mask = suspendCancellableCoroutine { cont ->
        segmenter.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result ->
                val buffer = result.buffer
                buffer.rewind()
                val n = result.width * result.height
                val values = FloatArray(n)
                for (i in 0 until n) values[i] = buffer.float
                cont.resume(Mask(result.width, result.height, values))
            }
            .addOnFailureListener { cont.resumeWithException(it) }
    }

    private class Background(val w: Int, val h: Int, val px: IntArray)

    /** Background blurred at 1/8 size; the person's pixels are left out of the blur (no halo). */
    private fun blurredBackground(full: Bitmap, mask: FloatArray, mw: Int, mh: Int): Background {
        val bw = max(1, full.width / BG_SCALE)
        val bh = max(1, full.height / BG_SCALE)
        val scaled = Bitmap.createScaledBitmap(full, bw, bh, true)
        val px = IntArray(bw * bh)
        scaled.getPixels(px, 0, bw, 0, 0, bw, bh)
        if (scaled !== full) scaled.recycle()

        val r = FloatArray(px.size)
        val g = FloatArray(px.size)
        val b = FloatArray(px.size)
        val wt = FloatArray(px.size)
        val pr = FloatArray(px.size)
        val pg = FloatArray(px.size)
        val pb = FloatArray(px.size)
        for (y in 0 until bh) {
            val my = min(mh - 1, y * mh / bh)
            for (x in 0 until bw) {
                val mx = min(mw - 1, x * mw / bw)
                val i = y * bw + x
                val c = px[i]
                val weight = (1f - mask[my * mw + mx]).coerceIn(0f, 1f)
                val cr = (c shr 16 and 255).toFloat()
                val cg = (c shr 8 and 255).toFloat()
                val cb = (c and 255).toFloat()
                pr[i] = cr
                pg[i] = cg
                pb[i] = cb
                r[i] = cr * weight
                g[i] = cg * weight
                b[i] = cb * weight
                wt[i] = weight
            }
        }
        val radius = max(2, max(bw, bh) / 45)
        for (arr in listOf(r, g, b, wt, pr, pg, pb)) {
            repeat(3) { Blur.box(arr, bw, bh, radius) }
        }
        for (i in px.indices) {
            val weight = wt[i]
            val cr: Float
            val cg: Float
            val cb: Float
            if (weight > 0.02f) {
                cr = r[i] / weight
                cg = g[i] / weight
                cb = b[i] / weight
            } else {
                cr = pr[i]
                cg = pg[i]
                cb = pb[i]
            }
            px[i] = (0xFF shl 24) or
                (cr.roundToInt().coerceIn(0, 255) shl 16) or
                (cg.roundToInt().coerceIn(0, 255) shl 8) or
                cb.roundToInt().coerceIn(0, 255)
        }
        return Background(bw, bh, px)
    }

    private fun composite(full: Bitmap, mask: FloatArray, mw: Int, mh: Int, bg: Background, progress: (Float) -> Unit) {
        val w = full.width
        val h = full.height
        val mx0 = IntArray(w)
        val mx1 = IntArray(w)
        val mfx = FloatArray(w)
        val bx0 = IntArray(w)
        val bx1 = IntArray(w)
        val bfx = FloatArray(w)
        for (x in 0 until w) {
            val m = ((x + 0.5f) * mw / w - 0.5f).coerceIn(0f, (mw - 1).toFloat())
            mx0[x] = m.toInt(); mx1[x] = min(mx0[x] + 1, mw - 1); mfx[x] = m - mx0[x]
            val b = ((x + 0.5f) * bg.w / w - 0.5f).coerceIn(0f, (bg.w - 1).toFloat())
            bx0[x] = b.toInt(); bx1[x] = min(bx0[x] + 1, bg.w - 1); bfx[x] = b - bx0[x]
        }
        val row = IntArray(w)
        for (y in 0 until h) {
            val my = ((y + 0.5f) * mh / h - 0.5f).coerceIn(0f, (mh - 1).toFloat())
            val mj0 = my.toInt()
            val mj1 = min(mj0 + 1, mh - 1)
            val mfy = my - mj0
            val by = ((y + 0.5f) * bg.h / h - 0.5f).coerceIn(0f, (bg.h - 1).toFloat())
            val bj0 = by.toInt()
            val bj1 = min(bj0 + 1, bg.h - 1)
            val bfy = by - bj0
            full.getPixels(row, 0, w, 0, y, w, 1)
            var changed = false
            for (x in 0 until w) {
                val a = mask[mj0 * mw + mx0[x]]
                val b = mask[mj0 * mw + mx1[x]]
                val c = mask[mj1 * mw + mx0[x]]
                val d = mask[mj1 * mw + mx1[x]]
                val top = a + (b - a) * mfx[x]
                val m = top + (c + (d - c) * mfx[x] - top) * mfy
                if (m >= 0.995f) continue
                val p00 = bg.px[bj0 * bg.w + bx0[x]]
                val p01 = bg.px[bj0 * bg.w + bx1[x]]
                val p10 = bg.px[bj1 * bg.w + bx0[x]]
                val p11 = bg.px[bj1 * bg.w + bx1[x]]
                val fx = bfx[x]
                val br = lerp2(p00 shr 16 and 255, p01 shr 16 and 255, p10 shr 16 and 255, p11 shr 16 and 255, fx, bfy)
                val bgc = lerp2(p00 shr 8 and 255, p01 shr 8 and 255, p10 shr 8 and 255, p11 shr 8 and 255, fx, bfy)
                val bb = lerp2(p00 and 255, p01 and 255, p10 and 255, p11 and 255, fx, bfy)
                val f = row[x]
                val inv = 1f - m
                val r = ((f shr 16 and 255) * m + br * inv).roundToInt().coerceIn(0, 255)
                val g = ((f shr 8 and 255) * m + bgc * inv).roundToInt().coerceIn(0, 255)
                val bl = ((f and 255) * m + bb * inv).roundToInt().coerceIn(0, 255)
                row[x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
                changed = true
            }
            if (changed) full.setPixels(row, 0, w, 0, y, w, 1)
            if (y % 256 == 0) progress(0.7f + 0.3f * y / h)
        }
        progress(1f)
    }

    private fun lerp2(p00: Int, p01: Int, p10: Int, p11: Int, fx: Float, fy: Float): Float {
        val top = p00 + (p01 - p00) * fx
        val bottom = p10 + (p11 - p10) * fx
        return top + (bottom - top) * fy
    }

    private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun decodeMutable(jpeg: ByteArray): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > MAX_SIDE) sample *= 2
        val o = BitmapFactory.Options().apply {
            inSampleSize = sample
            inMutable = true
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, o) ?: error("Couldn't decode the photo")
    }
}
