package com.tsetingdms.glasscamera.process

import android.graphics.Bitmap
import com.tsetingdms.glasscamera.ai.Scene
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min

/** How an AI scene tunes the finished photo. All mild: "a bit nicer", not a filter. */
class Look(
    /** Saturation, vibrance-style: dull colours change more than vivid ones. */
    val saturation: Float,
    /** Positive = warmer (red up, blue down), negative = cooler. */
    val warmth: Float,
    /** Mid-tone contrast (an S-curve on brightness). */
    val contrast: Float,
    /** Extra saturation for green / blue areas (foliage, sky, water). */
    val greens: Float = 0f,
    val blues: Float = 0f,
)

object SceneLook {
    private const val ROWS = 64

    fun of(scene: Scene): Look? = when (scene) {
        Scene.NONE -> null
        Scene.FOOD -> Look(saturation = 1.12f, warmth = 0.04f, contrast = 0.10f)
        Scene.GREENERY -> Look(saturation = 1.04f, warmth = 0.01f, contrast = 0.08f, greens = 0.18f, blues = 0.05f)
        Scene.LANDSCAPE -> Look(saturation = 1.04f, warmth = -0.01f, contrast = 0.08f, greens = 0.05f, blues = 0.18f)
        Scene.SUNSET -> Look(saturation = 1.15f, warmth = 0.06f, contrast = 0.10f)
        // Skin: slightly less saturation keeps faces from going orange.
        Scene.PEOPLE -> Look(saturation = 0.97f, warmth = 0.02f, contrast = 0.03f)
        Scene.ANIMALS -> Look(saturation = 1.05f, warmth = 0.01f, contrast = 0.08f)
        Scene.TEXT -> Look(saturation = 0.92f, warmth = 0f, contrast = 0.16f)
    }

    /** Applies [look] to [bitmap] in place, in parallel strips. Immutable bitmaps are left alone. */
    suspend fun apply(bitmap: Bitmap, look: Look) {
        if (!bitmap.isMutable) return
        val w = bitmap.width
        val h = bitmap.height
        val strips = (h + ROWS - 1) / ROWS
        val next = AtomicInteger(0)
        val workers = max(1, min(4, Runtime.getRuntime().availableProcessors() - 1))
        coroutineScope {
            (0 until workers).map {
                async(Dispatchers.Default) {
                    val px = IntArray(w * ROWS)
                    while (true) {
                        val s = next.getAndIncrement()
                        if (s >= strips) break
                        val y0 = s * ROWS
                        val n = min(ROWS, h - y0)
                        synchronized(bitmap) { bitmap.getPixels(px, 0, w, 0, y0, w, n) }
                        for (i in 0 until w * n) px[i] = pixel(px[i], look)
                        synchronized(bitmap) { bitmap.setPixels(px, 0, w, 0, y0, w, n) }
                    }
                }
            }.awaitAll()
        }
    }

    private fun pixel(c: Int, k: Look): Int {
        var r = (c shr 16 and 255) * (1f + k.warmth)
        var g = (c shr 8 and 255).toFloat()
        var b = (c and 255) * (1f - k.warmth)
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val chroma = ((mx - mn) / 255f).coerceIn(0f, 1f)
        val spread = mx - mn + 1f
        var s = 1f + (k.saturation - 1f) * (1f - chroma)
        if (k.greens != 0f && g > r && g > b) s += k.greens * (g - max(r, b)) / spread * (1f - 0.5f * chroma)
        if (k.blues != 0f && b > r && b > g) s += k.blues * (b - max(r, g)) / spread * (1f - 0.5f * chroma)
        val y = 0.299f * r + 0.587f * g + 0.114f * b
        r = y + (r - y) * s
        g = y + (g - y) * s
        b = y + (b - y) * s
        val yn = (y / 255f).coerceIn(0f, 1f)
        val dy = 255f * k.contrast * (yn - 0.5f) * 4f * yn * (1f - yn)
        return (0xFF shl 24) or (clamp(r + dy) shl 16) or (clamp(g + dy) shl 8) or clamp(b + dy)
    }

    private fun clamp(v: Float) = (v + 0.5f).toInt().coerceIn(0, 255)
}
