package com.tsetingdms.glasscamera.look

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min

/** Colour looks (3D LUTs in `assets/looks/<asset>.png`); applied live to the viewfinder and video, and to photos. */
enum class ColorLook(val label: String, val asset: String?) {
    NONE("Off", null),
    AMBER("Amber", "amber"),
    PALE_GREEN("Pale Green", "pale_green"),
    HARBOUR("Harbour", "harbour_blue"),
    CYAN("Cyan", "island_cyan"),
    GOLDEN("Golden", "avenue_star"),
    NEON("Neon", "mong_kok"),
}

/**
 * A 3D LUT of [size]³ entries, stored as a PNG atlas: blue slices side by side (width size²), red across each slice,
 * green down (height size). The atlas is uploaded as-is to the GPU; [table] serves the CPU path.
 */
class Lut(val size: Int, val atlas: Bitmap) {
    /** Packed 0xRRGGBB, index (b * size + g) * size + r. */
    val table = IntArray(size * size * size)

    init {
        val row = IntArray(size * size)
        for (g in 0 until size) {
            atlas.getPixels(row, 0, size * size, 0, g, size * size, 1)
            for (b in 0 until size) {
                for (r in 0 until size) table[(b * size + g) * size + r] = row[b * size + r] and 0xFFFFFF
            }
        }
    }
}

object Looks {
    private const val ROWS = 64
    private val cache = HashMap<ColorLook, Lut>()

    /** The look's LUT, loaded from assets once (null for [ColorLook.NONE] or if the asset is missing). */
    suspend fun load(context: Context, look: ColorLook): Lut? {
        val asset = look.asset ?: return null
        synchronized(cache) { cache[look]?.let { return it } }
        val lut = withContext(Dispatchers.IO) {
            runCatching {
                context.assets.open("looks/$asset.png").use { input ->
                    val o = BitmapFactory.Options().apply {
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                        inScaled = false
                    }
                    val bmp = BitmapFactory.decodeStream(input, null, o) ?: return@use null
                    Lut(bmp.height, bmp)
                }
            }.getOrNull()
        } ?: return null
        synchronized(cache) { cache[look] = lut }
        return lut
    }

    /** Applies [lut] to [bitmap] in place ([amount] 0..1 blends with the original), in parallel strips. */
    suspend fun apply(bitmap: Bitmap, lut: Lut, amount: Float) {
        if (!bitmap.isMutable || amount <= 0f) return
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
                        for (i in 0 until w * n) px[i] = grade(px[i], lut, amount)
                        synchronized(bitmap) { bitmap.setPixels(px, 0, w, 0, y0, w, n) }
                    }
                }
            }.awaitAll()
        }
    }

    /** Tetrahedral interpolation (4 table reads instead of trilinear's 8, and no colour fringing on grey). */
    private fun grade(c: Int, lut: Lut, amount: Float): Int {
        val n = lut.size
        val t = lut.table
        val scale = (n - 1) / 255f
        val ir = (c shr 16 and 255)
        val ig = (c shr 8 and 255)
        val ib = (c and 255)
        val fr = ir * scale
        val fg = ig * scale
        val fb = ib * scale
        val r0 = min(fr.toInt(), n - 2)
        val g0 = min(fg.toInt(), n - 2)
        val b0 = min(fb.toInt(), n - 2)
        val dr = fr - r0
        val dg = fg - g0
        val db = fb - b0
        val i000 = (b0 * n + g0) * n + r0
        val sr = 1
        val sg = n
        val sb = n * n
        val c000 = t[i000]
        val c111 = t[i000 + sr + sg + sb]
        val w0: Float
        val w1: Float
        val w2: Float
        val w3: Float
        val ca: Int
        val cb: Int
        if (dr > dg) {
            if (dg > db) {
                w0 = 1 - dr; w1 = dr - dg; w2 = dg - db; w3 = db
                ca = t[i000 + sr]; cb = t[i000 + sr + sg]
            } else if (dr > db) {
                w0 = 1 - dr; w1 = dr - db; w2 = db - dg; w3 = dg
                ca = t[i000 + sr]; cb = t[i000 + sr + sb]
            } else {
                w0 = 1 - db; w1 = db - dr; w2 = dr - dg; w3 = dg
                ca = t[i000 + sb]; cb = t[i000 + sr + sb]
            }
        } else {
            if (db > dg) {
                w0 = 1 - db; w1 = db - dg; w2 = dg - dr; w3 = dr
                ca = t[i000 + sb]; cb = t[i000 + sg + sb]
            } else if (db > dr) {
                w0 = 1 - dg; w1 = dg - db; w2 = db - dr; w3 = dr
                ca = t[i000 + sg]; cb = t[i000 + sg + sb]
            } else {
                w0 = 1 - dg; w1 = dg - dr; w2 = dr - db; w3 = db
                ca = t[i000 + sg]; cb = t[i000 + sr + sg]
            }
        }
        var r = (c000 shr 16 and 255) * w0 + (ca shr 16 and 255) * w1 + (cb shr 16 and 255) * w2 + (c111 shr 16 and 255) * w3
        var g = (c000 shr 8 and 255) * w0 + (ca shr 8 and 255) * w1 + (cb shr 8 and 255) * w2 + (c111 shr 8 and 255) * w3
        var b = (c000 and 255) * w0 + (ca and 255) * w1 + (cb and 255) * w2 + (c111 and 255) * w3
        if (amount < 1f) {
            r = ir + (r - ir) * amount
            g = ig + (g - ig) * amount
            b = ib + (b - ib) * amount
        }
        return (0xFF shl 24) or (((r + 0.5f).toInt().coerceIn(0, 255)) shl 16) or
            (((g + 0.5f).toInt().coerceIn(0, 255)) shl 8) or (b + 0.5f).toInt().coerceIn(0, 255)
    }
}
