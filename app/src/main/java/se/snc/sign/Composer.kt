package se.snc.sign

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Ett signaturfält, ett foto eller en stämpel. Läge och storlek anges som andel av sidan (0–1),
 * rot är vridningen i grader (används för stämplar).
 * file = signaturens eller fotots bild (kopierad till dokumentets egen mapp så att den aldrig ändras i efterhand).
 * signer = vem som ska signera fältet (för "Väntar på …").
 */
data class Field(
    val id: Long,
    val type: Type,
    val page: Int,
    var x: Float, var y: Float, var w: Float, var h: Float,
    val stampId: String? = null,
    var sigFile: String? = null,
    var name: String = "",
    var date: String = "",
    var rot: Float = 0f,
    var signer: String = "",
    var profile: Int = 0,
) {
    enum class Type { SIGN, PHOTO, STAMP }
    val done get() = type != Type.SIGN || sigFile != null

    fun toJson(): org.json.JSONObject = org.json.JSONObject()
        .put("id", id).put("type", type.name).put("page", page)
        .put("x", x.toDouble()).put("y", y.toDouble()).put("w", w.toDouble()).put("h", h.toDouble())
        .put("stamp", stampId ?: "").put("file", sigFile ?: "").put("name", name).put("date", date)
        .put("rot", rot.toDouble()).put("signer", signer).put("profile", profile)

    companion object {
        fun fromJson(o: org.json.JSONObject) = Field(
            id = o.optLong("id"), type = runCatching { Type.valueOf(o.optString("type")) }.getOrDefault(Type.STAMP),
            page = o.optInt("page"),
            x = o.optDouble("x").toFloat(), y = o.optDouble("y").toFloat(), w = o.optDouble("w").toFloat(), h = o.optDouble("h").toFloat(),
            stampId = o.optString("stamp").ifBlank { null }, sigFile = o.optString("file").ifBlank { null },
            name = o.optString("name"), date = o.optString("date"), rot = o.optDouble("rot", 0.0).toFloat(),
            signer = o.optString("signer"), profile = o.optInt("profile"),
        )
    }
}

/** Ritar ett fält: signaturen med namn och datum under, fotot eller stämpeln – skalat så att allt får plats. */
object Composer {

    private val cache = HashMap<String, Bitmap>()

    fun forget(path: String) { cache.remove(path) }

    private fun load(path: String): Bitmap? = cache[path] ?: BitmapFactory.decodeFile(path)?.also { cache[path] = it }

    fun today(ctx: Context): String =
        LocalDate.now().format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(Lang.locale(ctx)))

    fun compose(ctx: Context, f: Field, wPx: Int, hPx: Int): Bitmap? {
        val w = wPx.coerceIn(1, 3000); val h = hPx.coerceIn(1, 3000)
        if (f.type == Field.Type.STAMP) {
            val st = f.stampId?.let { Store.stampBitmap(ctx, it) } ?: return null
            return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { contain(Canvas(it), st, RectF(0f, 0f, w.toFloat(), h.toFloat()), false) }
        }
        if (f.type == Field.Type.PHOTO) {
            val photo = (f.sigFile?.takeIf { java.io.File(it).exists() } ?: Profile(ctx, 1).photoFile.path.takeIf { java.io.File(it).exists() })
                ?.let(::load) ?: return null
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            // Fotot fyller fältet med bibehållna proportioner och rundade hörn
            val ar = photo.width.toFloat() / photo.height
            var pw = w.toFloat(); var ph = pw / ar
            if (ph > h) { ph = h.toFloat(); pw = ph * ar }
            val rect = RectF((w - pw) / 2f, (h - ph) / 2f, (w + pw) / 2f, (h + ph) / 2f)
            val r = minOf(pw, ph) * 0.06f
            c.save()
            c.clipPath(Path().apply { addRoundRect(rect, r, r, Path.Direction.CW) })
            c.drawBitmap(photo, null, rect, Paint(Paint.FILTER_BITMAP_FLAG))
            c.restore()
            c.drawRoundRect(rect, r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = maxOf(1f, w * 0.01f); color = 0x590F2A6B
            })
            return out
        }
        val sig = f.sigFile?.let(::load) ?: return null
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)

        val lines = listOfNotNull(f.name.takeIf { it.isNotBlank() }?.let { it to true }, f.date.takeIf { it.isNotBlank() }?.let { it to false })
        val font = maxOf(6f, minOf(h * 0.13f, w * 0.06f))
        val lineH = font * 1.25f
        val textBlock = if (lines.isEmpty()) 0f else lines.size * lineH + font * 0.35f
        val sx = 0f
        val areaH = h - textBlock
        if (areaH > 4f) contain(c, sig, RectF(sx, 0f, w.toFloat(), areaH), true)

        if (lines.isNotEmpty()) {
            var y = areaH + font * 0.15f
            c.drawLine(sx, y, w.toFloat(), y, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x730F2A6B; strokeWidth = maxOf(1f, font * 0.06f) })
            y += font * 0.2f
            val sans: Typeface = ResourcesCompat.getFont(ctx, R.font.jakarta_medium) ?: Typeface.DEFAULT
            val bold: Typeface = ResourcesCompat.getFont(ctx, R.font.jakarta_bold) ?: Typeface.DEFAULT_BOLD
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1B2440.toInt(); textSize = font }
            for ((text, isBold) in lines) {
                p.typeface = if (isBold) bold else sans
                c.drawText(ellipsize(p, text, w - sx), sx, y - p.ascent(), p)
                y += lineH
            }
        }
        return out
    }

    /**
     * Ritar fältet för export, med vridning. Returnerar bilden och dess ram i sidan (andelar 0–1).
     * Vid vridning blir ramen den omslutande rektangeln, så att bilden kan läggas in rakt i PDF och Word.
     */
    fun render(ctx: Context, f: Field, pageWpt: Float, pageHpt: Float, pxPerPt: Float): Pair<Bitmap, RectF>? {
        val wPt = f.w * pageWpt; val hPt = f.h * pageHpt
        val base = compose(ctx, f, (wPt * pxPerPt).toInt(), (hPt * pxPerPt).toInt()) ?: return null
        val rect = RectF(f.x, f.y, f.x + f.w, f.y + f.h)
        if (kotlin.math.abs(f.rot) < 0.1f) return base to rect
        val img = Bitmap.createBitmap(base, 0, 0, base.width, base.height, android.graphics.Matrix().apply { postRotate(f.rot) }, true)
        val a = Math.toRadians(f.rot.toDouble())
        val c = kotlin.math.abs(kotlin.math.cos(a)).toFloat(); val s = kotlin.math.abs(kotlin.math.sin(a)).toFloat()
        val bw = wPt * c + hPt * s; val bh = wPt * s + hPt * c
        val cx = (f.x + f.w / 2f) * pageWpt; val cy = (f.y + f.h / 2f) * pageHpt
        return img to RectF((cx - bw / 2f) / pageWpt, (cy - bh / 2f) / pageHpt, (cx + bw / 2f) / pageWpt, (cy + bh / 2f) / pageHpt)
    }

    private fun contain(c: Canvas, b: Bitmap, r: RectF, bottom: Boolean) {
        val s = minOf(r.width() / b.width, r.height() / b.height)
        val dw = b.width * s; val dh = b.height * s
        val left = r.left + (r.width() - dw) / 2f
        val top = if (bottom) r.bottom - dh else r.top + (r.height() - dh) / 2f
        c.drawBitmap(b, null, RectF(left, top, left + dw, top + dh), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
    }

    private fun ellipsize(p: Paint, s: String, max: Float): String {
        if (p.measureText(s) <= max) return s
        var t = s
        while (t.length > 1 && p.measureText("$t…") > max) t = t.dropLast(1)
        return "$t…"
    }
}
