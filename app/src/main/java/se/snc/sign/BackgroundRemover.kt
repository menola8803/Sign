package se.snc.sign

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Tar bort bakgrunden från en fotad eller inläst logga. Det som blir kvar behåller sina färger,
 * det som tas bort blir genomskinligt.
 *
 * Så fungerar det:
 * 1. Bilden jämnas ut lite (mot brus och randiga skärmmönster) – bara för besluten, färgerna tas från originalet.
 * 2. Bakgrunden växer in från kanterna: en pixel räknas som bakgrund om den liknar grannen som redan är bakgrund.
 *    Därför klarar den ojämnt ljus och skuggor, men stannar vid loggans kanter. Färger inne i loggan rörs inte.
 * 3. Små lösa prickar (brus, skärmmönster) räknas också som bakgrund.
 * 4. Kanterna blir mjuka och bakgrundens färgstick tas bort ur dem.
 *
 * strength −1..1: lägre tar bort mindre, högre tar bort mer.
 */
object BackgroundRemover {

    class Result(val alpha: FloatArray, val bgR: Float, val bgG: Float, val bgB: Float)

    /** Utjämnad bild (box 5×5) som packad RGB. Används för besluten, inte för färgerna. */
    fun blur(px: IntArray, w: Int, h: Int, r: Int = 2): IntArray {
        val n = w * h
        val tmpR = IntArray(n); val tmpG = IntArray(n); val tmpB = IntArray(n)
        // vågrätt
        for (y in 0 until h) {
            var sr = 0; var sg = 0; var sb = 0; var cnt = 0
            val row = y * w
            for (x in -r until w + r) {
                val add = x + r
                if (add in 0 until w) { val c = px[row + add]; sr += (c shr 16) and 255; sg += (c shr 8) and 255; sb += c and 255; cnt++ }
                val rem = x - r - 1
                if (rem in 0 until w) { val c = px[row + rem]; sr -= (c shr 16) and 255; sg -= (c shr 8) and 255; sb -= c and 255; cnt-- }
                if (x in 0 until w && cnt > 0) { tmpR[row + x] = sr / cnt; tmpG[row + x] = sg / cnt; tmpB[row + x] = sb / cnt }
            }
        }
        // lodrätt
        val out = IntArray(n)
        for (x in 0 until w) {
            var sr = 0; var sg = 0; var sb = 0; var cnt = 0
            for (y in -r until h + r) {
                val add = y + r
                if (add in 0 until h) { val i = add * w + x; sr += tmpR[i]; sg += tmpG[i]; sb += tmpB[i]; cnt++ }
                val rem = y - r - 1
                if (rem in 0 until h) { val i = rem * w + x; sr -= tmpR[i]; sg -= tmpG[i]; sb -= tmpB[i]; cnt-- }
                if (y in 0 until h && cnt > 0) out[y * w + x] = ((sr / cnt) shl 16) or ((sg / cnt) shl 8) or (sb / cnt)
            }
        }
        return out
    }

    private fun absSum(a: Int, b: Int) =
        abs(((a shr 16) and 255) - ((b shr 16) and 255)) + abs(((a shr 8) and 255) - ((b shr 8) and 255)) + abs((a and 255) - (b and 255))

    fun auto(px: IntArray, w: Int, h: Int, strength: Float): Result {
        val n = w * h
        val b = blur(px, w, h)
        val bw = max(2, min(w, h) / 60)

        // Bakgrundens färg: median av kantpixlarna
        val border = ArrayList<Int>()
        for (y in 0 until h) for (x in 0 until w) if (x < bw || y < bw || x >= w - bw || y >= h - bw) border.add(y * w + x)
        fun median(sh: Int) = border.map { (b[it] shr sh) and 255 }.sorted()[border.size / 2]
        val mr = median(16); val mg = median(8); val mb = median(0)
        val med = (mr shl 16) or (mg shl 8) or mb
        fun eu(c: Int): Float {
            val dr = (((c shr 16) and 255) - mr) / 255f; val dg = (((c shr 8) and 255) - mg) / 255f; val db = ((c and 255) - mb) / 255f
            return sqrt(dr * dr + dg * dg + db * db)
        }
        val bd = border.map { eu(b[it]) }.sorted()
        val p90 = bd[(bd.size * 0.9f).toInt().coerceAtMost(bd.size - 1)]
        val s = ((strength.coerceIn(-1f, 1f)) + 1f) / 2f
        val tGlobal = min(0.55f, p90 * 1.6f + 0.12f + 0.18f * s)
        val tStep = 0.020f + 0.030f * s
        val stepLim = (tStep * 1.7f * 255f).toInt()
        val globalLim = (tGlobal * 3.2f * 255f).toInt()

        // Bakgrunden växer in från kanterna
        val bg = BooleanArray(n)
        val queue = IntArray(n)
        var head = 0; var tail = 0
        for (i in border) if (!bg[i] && eu(b[i]) < tGlobal) { bg[i] = true; queue[tail++] = i }
        while (head < tail) {
            val i = queue[head++]
            val x = i % w; val y = i / w
            val c = b[i]
            fun visit(j: Int) {
                if (!bg[j] && absSum(b[j], c) < stepLim && absSum(b[j], med) < globalLim) { bg[j] = true; queue[tail++] = j }
            }
            if (x > 0) visit(i - 1)
            if (x < w - 1) visit(i + 1)
            if (y > 0) visit(i - w)
            if (y < h - 1) visit(i + w)
        }

        // Små lösa prickar som inte hänger ihop med loggan blir också bakgrund
        val seen = BooleanArray(n)
        val minSize = max(30, (n * 0.0015f).toInt())
        for (start in 0 until n) {
            if (bg[start] || seen[start]) continue
            head = 0; tail = 0
            queue[tail++] = start; seen[start] = true
            while (head < tail) {
                val i = queue[head++]
                val x = i % w; val y = i / w
                if (x > 0 && !bg[i - 1] && !seen[i - 1]) { seen[i - 1] = true; queue[tail++] = i - 1 }
                if (x < w - 1 && !bg[i + 1] && !seen[i + 1]) { seen[i + 1] = true; queue[tail++] = i + 1 }
                if (y > 0 && !bg[i - w] && !seen[i - w]) { seen[i - w] = true; queue[tail++] = i - w }
                if (y < h - 1 && !bg[i + w] && !seen[i + w]) { seen[i + w] = true; queue[tail++] = i + w }
            }
            if (tail < minSize) for (k in 0 until tail) bg[queue[k]] = true
        }

        // Bakgrundens medelfärg
        var sr = 0L; var sg = 0L; var sb = 0L; var cnt = 0L
        for (i in 0 until n) if (bg[i]) { val c = b[i]; sr += (c shr 16) and 255; sg += (c shr 8) and 255; sb += c and 255; cnt++ }
        val br = if (cnt > 0) sr / cnt / 255f else mr / 255f
        val bgc = if (cnt > 0) sg / cnt / 255f else mg / 255f
        val bb = if (cnt > 0) sb / cnt / 255f else mb / 255f

        // Genomskinlighet: bakgrund 0, logga 1, mjuka kanter där de möts
        val alpha = FloatArray(n)
        for (i in 0 until n) {
            if (bg[i]) continue
            val x = i % w; val y = i / w
            var nearBg = false
            loop@ for (dy in -2..2) {
                val yy = y + dy
                if (yy < 0 || yy >= h) continue
                for (dx in -2..2) {
                    val xx = x + dx
                    if (xx < 0 || xx >= w) continue
                    if (bg[yy * w + xx]) { nearBg = true; break@loop }
                }
            }
            alpha[i] = if (!nearBg) 1f else {
                val c = px[i]
                val dr = ((c shr 16) and 255) / 255f - br; val dg = ((c shr 8) and 255) / 255f - bgc; val db = (c and 255) / 255f - bb
                ((sqrt(dr * dr + dg * dg + db * db) - tStep) / 0.30f).coerceIn(0f, 1f)
            }
        }
        return Result(alpha, br, bgc, bb)
    }

    /**
     * "Ta bort samma färg": markerar pixlar som liknar färgen där man tryckte.
     * contiguous = bara det sammanhängande området, annars överallt i bilden.
     * tolerance 0–1.
     */
    fun wand(blur: IntArray, w: Int, h: Int, x: Int, y: Int, tolerance: Float, contiguous: Boolean, out: ByteArray, value: Byte) {
        if (x !in 0 until w || y !in 0 until h) return
        val seed = blur[y * w + x]
        val lim = (8 + tolerance.coerceIn(0f, 1f) * 220).toInt()
        if (!contiguous) {
            for (i in blur.indices) if (absSum(blur[i], seed) < lim) out[i] = value
            return
        }
        val seen = BooleanArray(w * h)
        val queue = IntArray(w * h)
        var head = 0; var tail = 0
        val s = y * w + x
        queue[tail++] = s; seen[s] = true
        while (head < tail) {
            val i = queue[head++]
            out[i] = value
            val cx = i % w; val cy = i / w
            fun visit(j: Int) { if (!seen[j] && absSum(blur[j], seed) < lim) { seen[j] = true; queue[tail++] = j } }
            if (cx > 0) visit(i - 1)
            if (cx < w - 1) visit(i + 1)
            if (cy > 0) visit(i - w)
            if (cy < h - 1) visit(i + w)
        }
    }

    /** Gör vita och grå delar mörka så att de syns på vitt papper. Guld och färger behålls. */
    fun darken(c: Int): Int {
        val a = (c ushr 24) and 255
        if (a == 0) return c
        val r = ((c shr 16) and 255) / 255f; val g = ((c shr 8) and 255) / 255f; val bl = (c and 255) / 255f
        val mx = max(r, max(g, bl)); val mn = min(r, min(g, bl))
        val sat = if (mx > 0f) (mx - mn) / mx else 0f
        val light = (mx + mn) / 2f
        val k = ((0.28f - sat) / 0.18f).coerceIn(0f, 1f)
        if (k <= 0f) return c
        val d = (0.12f + 0.55f * (1f - light)).coerceIn(0f, 1f)
        val nr = r * (1 - k) + d * 0.95f * k; val ng = g * (1 - k) + d * 0.97f * k; val nb = bl * (1 - k) + (d * 1.05f).coerceAtMost(1f) * k
        return (a shl 24) or ((nr * 255).toInt().coerceIn(0, 255) shl 16) or ((ng * 255).toInt().coerceIn(0, 255) shl 8) or (nb * 255).toInt().coerceIn(0, 255)
    }
}
