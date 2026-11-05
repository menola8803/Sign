package se.snc.sign

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Tar bort bakgrunden från en logga så att bara symbol och text blir kvar.
 * Bakgrundsfärgen hämtas från bildens kanter. Pixlar som liknar den blir genomskinliga,
 * kanterna blir mjuka och bakgrundens färgstick tas bort. Bilden beskärs till innehållet.
 *
 * strength 0–1: högre tar bort mer.
 * darkenWhite: vita och grå delar görs mörka så att de syns på vitt papper; guld och färger behålls.
 */
object BackgroundRemover {

    fun process(src: Bitmap, removeBackground: Boolean, strength: Float, darkenWhite: Boolean): Bitmap? {
        val b = Store.scaleDown(src, 1400).copy(Bitmap.Config.ARGB_8888, false)
        val w = b.width; val h = b.height
        val px = IntArray(w * h)
        b.getPixels(px, 0, w, 0, 0, w, h)
        val out = IntArray(w * h)

        if (removeBackground) {
            // Bakgrundsfärg = median av kantpixlarna
            val bw = max(2, min(w, h) / 80)
            val border = ArrayList<Int>()
            for (y in 0 until h) for (x in 0 until w) {
                if (x < bw || y < bw || x >= w - bw || y >= h - bw) border.add(px[y * w + x])
            }
            val br = border.map { Color.red(it) }.sorted()[border.size / 2] / 255f
            val bg = border.map { Color.green(it) }.sorted()[border.size / 2] / 255f
            val bb = border.map { Color.blue(it) }.sorted()[border.size / 2] / 255f
            fun dist(c: Int): Float {
                val r = Color.red(c) / 255f - br; val g = Color.green(c) / 255f - bg; val bl = Color.blue(c) / 255f - bb
                return sqrt(r * r + g * g + bl * bl)
            }
            val spread = border.map(::dist).sorted()[(border.size * 0.98f).toInt().coerceAtMost(border.size - 1)]
            val t0 = spread + 0.01f + 0.12f * strength.coerceIn(0f, 1f)
            val t1 = t0 + 0.18f
            for (i in px.indices) {
                val c = px[i]
                if (Color.alpha(c) < 10) { out[i] = 0; continue }
                val a = ((dist(c) - t0) / (t1 - t0)).coerceIn(0f, 1f)
                if (a <= 0f) { out[i] = 0; continue }
                // Avblandning: ta bort bakgrundens färg ur halvgenomskinliga kanter
                fun unmix(v: Float, base: Float) = ((v - (1 - a) * base) / a).coerceIn(0f, 1f)
                val r = unmix(Color.red(c) / 255f, br); val g = unmix(Color.green(c) / 255f, bg); val bl = unmix(Color.blue(c) / 255f, bb)
                out[i] = Color.argb((a * Color.alpha(c)).toInt(), (r * 255).toInt(), (g * 255).toInt(), (bl * 255).toInt())
            }
        } else {
            System.arraycopy(px, 0, out, 0, px.size)
        }

        if (darkenWhite) {
            for (i in out.indices) {
                val c = out[i]
                if (Color.alpha(c) == 0) continue
                val r = Color.red(c) / 255f; val g = Color.green(c) / 255f; val bl = Color.blue(c) / 255f
                val mx = max(r, max(g, bl)); val mn = min(r, min(g, bl))
                val sat = if (mx > 0f) (mx - mn) / mx else 0f
                val light = (mx + mn) / 2f
                val k = ((0.28f - sat) / 0.18f).coerceIn(0f, 1f) // 1 = grått/vitt
                if (k <= 0f) continue
                val d = (0.12f + 0.55f * (1f - light)).coerceIn(0f, 1f)
                val nr = r * (1 - k) + d * 0.95f * k; val ng = g * (1 - k) + d * 0.97f * k; val nb = bl * (1 - k) + (d * 1.05f).coerceAtMost(1f) * k
                out[i] = Color.argb(Color.alpha(c), (nr * 255).toInt(), (ng * 255).toInt(), (nb * 255).toInt())
            }
        }

        // Beskär till innehållet
        var minX = w; var minY = h; var maxX = -1; var maxY = -1
        for (y in 0 until h) for (x in 0 until w) if (Color.alpha(out[y * w + x]) > 25) {
            if (x < minX) minX = x; if (x > maxX) maxX = x
            if (y < minY) minY = y; if (y > maxY) maxY = y
        }
        if (maxX < 0) return null
        val pad = max(4, (max(maxX - minX, maxY - minY) * 0.02f).toInt())
        minX = max(0, minX - pad); minY = max(0, minY - pad); maxX = min(w - 1, maxX + pad); maxY = min(h - 1, maxY + pad)
        val full = Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
        return Bitmap.createBitmap(full, minX, minY, maxX - minX + 1, maxY - minY + 1)
    }
}
