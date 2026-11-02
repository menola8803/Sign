package se.snc.sign

import android.graphics.Bitmap
import android.graphics.Color

/**
 * Plockar ut bläcket ur ett foto av en signatur på papper (eller en infogad bild).
 * Papperet blir genomskinligt, bläcket behåller sin färg men blir mörkare och jämnare,
 * och bilden beskärs till själva signaturen. Bilder som redan är genomskinliga beskärs bara.
 */
object InkExtractor {

    fun extract(src: Bitmap): Bitmap? {
        val w = src.width; val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)

        val hasAlpha = src.hasAlpha() && px.count { Color.alpha(it) < 200 } > px.size / 20
        val out = IntArray(w * h)
        if (hasAlpha) {
            System.arraycopy(px, 0, out, 0, px.size)
        } else {
            // Papperets ljushet tas lokalt (rutnät) så att skuggor och ojämnt ljus inte blir bläck
            val lum = FloatArray(w * h) { i -> luma(px[i]) }
            val cell = maxOf(16, minOf(w, h) / 12)
            val gw = (w + cell - 1) / cell; val gh = (h + cell - 1) / cell
            val paper = FloatArray(gw * gh)
            val tmp = ArrayList<Float>()
            for (gy in 0 until gh) for (gx in 0 until gw) {
                tmp.clear()
                for (y in gy * cell until minOf(h, (gy + 1) * cell) step 2) for (x in gx * cell until minOf(w, (gx + 1) * cell) step 2) tmp.add(lum[y * w + x])
                tmp.sort()
                paper[gy * gw + gx] = tmp[(tmp.size * 0.9f).toInt().coerceAtMost(tmp.size - 1)]
            }
            for (y in 0 until h) for (x in 0 until w) {
                // Bilinjär interpolation av papperets ljushet
                val fx = (x.toFloat() / cell - 0.5f).coerceIn(0f, (gw - 1).toFloat())
                val fy = (y.toFloat() / cell - 0.5f).coerceIn(0f, (gh - 1).toFloat())
                val x0 = fx.toInt(); val y0 = fy.toInt()
                val x1 = minOf(x0 + 1, gw - 1); val y1 = minOf(y0 + 1, gh - 1)
                val tx = fx - x0; val ty = fy - y0
                val p = (paper[y0 * gw + x0] * (1 - tx) + paper[y0 * gw + x1] * tx) * (1 - ty) +
                    (paper[y1 * gw + x0] * (1 - tx) + paper[y1 * gw + x1] * tx) * ty
                val i = y * w + x
                val darkness = (p - lum[i]) / maxOf(p, 0.2f)       // 0 = papper, 1 = svart
                val a = ((darkness - 0.12f) / 0.33f).coerceIn(0f, 1f)
                if (a <= 0f) { out[i] = 0; continue }
                val c = px[i]
                // Mörka och mätta upp bläcket lite så att det syns tydligt på dokumentet
                val k = 0.55f
                out[i] = Color.argb((a * 255).toInt(), (Color.red(c) * k).toInt(), (Color.green(c) * k).toInt(), (Color.blue(c) * k).toInt())
            }
        }

        // Beskär till signaturen
        var minX = w; var minY = h; var maxX = -1; var maxY = -1
        for (y in 0 until h) for (x in 0 until w) if (Color.alpha(out[y * w + x]) > 60) {
            if (x < minX) minX = x; if (x > maxX) maxX = x
            if (y < minY) minY = y; if (y > maxY) maxY = y
        }
        if (maxX < 0 || (maxX - minX) < 8 || (maxY - minY) < 4) return null
        val pad = maxOf(4, (maxOf(maxX - minX, maxY - minY) * 0.03f).toInt())
        minX = maxOf(0, minX - pad); minY = maxOf(0, minY - pad)
        maxX = minOf(w - 1, maxX + pad); maxY = minOf(h - 1, maxY + pad)
        val full = Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
        return Bitmap.createBitmap(full, minX, minY, maxX - minX + 1, maxY - minY + 1)
    }

    private fun luma(c: Int) = (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) / 255f
}
