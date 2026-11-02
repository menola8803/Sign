package se.snc.sign

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat

/** Det inbyggda sigillet för Scandinavian Nexum Consulting AB, ritat i full upplösning. */
object Seal {
    const val ID = "snc-seal"
    /** Den runda S.N.C-loggan (bilden från företaget). Läggs i vänstra hörnet. */
    const val LOGO_ID = "snc-logo"
    private var logo: Bitmap? = null

    fun logo(ctx: Context): Bitmap =
        logo ?: android.graphics.BitmapFactory.decodeResource(ctx.resources, R.drawable.snc_logo).also { logo = it }
    private const val COLOR = 0xFF1D3A8A.toInt()
    private var cached: Bitmap? = null

    fun bitmap(ctx: Context, size: Int = 900): Bitmap {
        cached?.let { if (it.width == size) return it }
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val s = size / 400f
        c.scale(s, s)
        val serif: Typeface = ResourcesCompat.getFont(ctx, R.font.cinzel_bold) ?: Typeface.SERIF
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = COLOR }
        stroke.strokeWidth = 10f; c.drawCircle(200f, 200f, 190f, stroke)
        stroke.strokeWidth = 3f; c.drawCircle(200f, 200f, 172f, stroke)
        stroke.strokeWidth = 3f; c.drawCircle(200f, 200f, 112f, stroke)

        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR; typeface = serif; textAlign = Paint.Align.CENTER
        }
        // Text längs ringen: överst och nederst
        text.textSize = 26f; text.letterSpacing = 0.08f
        val top = Path().apply { addArc(RectF(58f, 58f, 342f, 342f), 180f, 180f) }
        c.drawTextOnPath("SCANDINAVIAN NEXUM", top, 0f, 0f, text)
        val bottom = Path().apply { addArc(RectF(38f, 38f, 362f, 362f), 180f, -180f) }
        c.drawTextOnPath("CONSULTING AB", bottom, 0f, 0f, text)

        text.textSize = 24f; text.letterSpacing = 0f
        c.drawText("★", 34f, 209f, text)
        c.drawText("★", 366f, 209f, text)

        text.textSize = 60f; text.letterSpacing = 0.05f
        c.drawText("S.N.C", 200f, 212f, text)
        stroke.strokeWidth = 3f
        c.drawLine(138f, 226f, 262f, 226f, stroke)
        text.textSize = 28f; text.letterSpacing = 0.3f
        c.drawText("AB", 204f, 262f, text)
        cached = b
        return b
    }
}
