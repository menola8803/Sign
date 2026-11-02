package se.snc.sign

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import androidx.core.content.res.ResourcesCompat
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow

/**
 * Signs egen startskärm. Alltid mörk. Samma upplägg som startskärmen i Trace:
 * en 390 x 844-duk som skalas till skärmen, en guldring som ritar upp sig, ett S med ett signaturdrag,
 * två punkter som kretsar, krusningar, ordmärket i Cinzel, appens tre funktioner och utvecklaren längst ner.
 */
@SuppressLint("ViewConstructor")
class StartScreenView(context: Context, private val onDone: (StartScreenView) -> Unit) : View(context) {

    companion object {
        private const val W0 = 390f
        private const val H0 = 844f
        private const val SHOW_MS = 3600L
        private const val SHOW_REDUCED_MS = 1200L
        private const val FADE_MS = 450L
        private const val MIN_SKIP_MS = 700L

        private val BG = Color.parseColor("#0B1416")
        private val TEAL = Color.parseColor("#17393E")
        private val PANEL = Color.parseColor("#132225")
        private val LINE = Color.parseColor("#2A4448")
        private val GOLD = Color.parseColor("#E8B04A")
        private val GOLD_DIM = Color.parseColor("#8A6D2E")
        private val GOLD_PALE = Color.parseColor("#F3D9A0")
        private val MINT = Color.parseColor("#5FC4B4")
        private val INK = Color.parseColor("#B7C6C3")
        private val DIM = Color.parseColor("#8FA5A2")

        private const val CX = 195f
        private const val CY = 292f
    }

    private val reduce = Settings.Global.getFloat(
        context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f
    ) == 0f
    private val born = SystemClock.uptimeMillis()
    private var finishing = false

    private val cinzel: Typeface = ResourcesCompat.getFont(context, R.font.cinzel_bold) ?: Typeface.SERIF
    private val sansBold: Typeface = ResourcesCompat.getFont(context, R.font.jakarta_bold) ?: Typeface.DEFAULT_BOLD
    private val sansMedium: Typeface = ResourcesCompat.getFont(context, R.font.jakarta_medium) ?: Typeface.DEFAULT

    private val tagline = context.getString(R.string.tagline)
    private val developer = context.getString(R.string.developer_line)
    private val privacy = context.getString(R.string.on_device_caps)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowBottom = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val part = Path()
    private val rect = RectF()
    private val bounds = android.graphics.Rect()

    // Signaturdraget under S:et, samma form som i ikonen
    private val swoosh = Path().apply {
        moveTo(CX - 40f, CY + 30f)
        cubicTo(CX - 26f, CY + 22f, CX - 14f, CY + 40f, CX - 2f, CY + 31f)
        cubicTo(CX + 10f, CY + 22f, CX + 20f, CY + 38f, CX + 40f, CY + 27f)
    }
    private val swooshMeasure = PathMeasure(swoosh, false)

    init {
        isClickable = true
        contentDescription = "Sign. $developer"
        postDelayed({ finish() }, if (reduce) SHOW_REDUCED_MS else SHOW_MS)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP && SystemClock.uptimeMillis() - born > MIN_SKIP_MS) finish()
        return true
    }

    private fun finish() {
        if (finishing) return
        finishing = true
        animate().alpha(0f).setDuration(FADE_MS).withEndAction { onDone(this) }.start()
    }

    // ---- Tid och rörelse ----

    private fun elapsed(): Float = if (reduce) 100_000f else (SystemClock.uptimeMillis() - born).toFloat()

    private fun ent(t: Float, delay: Float, dur: Float): Float {
        val x = ((t - delay) / dur).coerceIn(0f, 1f)
        return 1f - (1f - x).pow(3)
    }

    private fun loop(t: Float, period: Float, delay: Float = 0f): Float =
        if (reduce) 0.5f else ((t - delay).coerceAtLeast(0f) % period) / period

    private fun ping(t: Float, period: Float, delay: Float = 0f): Float =
        if (reduce) 0.5f else (0.5f - 0.5f * cos(2f * PI.toFloat() * loop(t, period, delay)))

    private fun pulse(t: Float, delay: Float): Float {
        if (reduce || t < delay) return 0f
        val p = (t - delay) % 3000f
        return when {
            p < 240f -> p / 240f
            p < 540f -> 1f
            p < 780f -> 1f - (p - 540f) / 240f
            else -> 0f
        }
    }

    private fun lerp(a: Float, b: Float, x: Float) = a + (b - a) * x

    private fun Paint.tint(c: Int, alpha: Float) {
        color = c
        this.alpha = (alpha.coerceIn(0f, 1f) * Color.alpha(c)).toInt()
    }

    // ---- Ritning ----

    override fun onDraw(canvas: Canvas) {
        val t = elapsed()
        canvas.drawColor(BG)

        val s = min(width / W0, height / H0)
        canvas.save()
        canvas.translate((width - W0 * s) / 2f, (height - H0 * s) / 2f)
        canvas.scale(s, s)

        drawGlow(canvas)
        drawPattern(canvas, t)
        drawRipples(canvas, t)
        drawLogo(canvas, t)
        drawWordmark(canvas, t)
        drawConnectors(canvas, t)
        drawFeature(canvas, t, 51f, context.getString(R.string.splash_documents), 850f, 1300f) { c, tt, x, y -> docIcon(c, tt, x, y) }
        drawFeature(canvas, t, 159f, context.getString(R.string.splash_sign), 1000f, 2000f) { c, tt, x, y -> penIcon(c, tt, x, y) }
        drawFeature(canvas, t, 267f, context.getString(R.string.splash_photo), 1150f, 2700f) { c, tt, x, y -> cameraIcon(c, tt, x, y) }
        drawFooter(canvas, t)

        canvas.restore()
        if (!reduce && !finishing) postInvalidateOnAnimation()
    }

    /** Grön glöd bakom loggan och en varm guldglöd längst ner, som i Trace. */
    private fun drawGlow(c: Canvas) {
        glow.shader = RadialGradient(
            CX, 278f, 330f,
            intArrayOf(TEAL, Color.argb(0, Color.red(TEAL), Color.green(TEAL), Color.blue(TEAL))),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
        )
        c.drawRect(-W0, -H0, W0 * 2, H0 * 2, glow)
        glowBottom.shader = RadialGradient(
            CX, H0 + 17f, 260f,
            intArrayOf(Color.argb(33, 232, 176, 74), Color.argb(0, 232, 176, 74)),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
        )
        c.drawRect(-W0, -H0, W0 * 2, H0 * 2, glowBottom)
    }

    // Bakgrund: ett svagt nät av punkter där signaturer färdas mellan dokumenten.
    private val nodes = arrayOf(
        floatArrayOf(34f, 92f, 600f), floatArrayOf(118f, 58f, 0f), floatArrayOf(206f, 112f, 1200f),
        floatArrayOf(300f, 66f, 300f), floatArrayOf(360f, 148f, 1800f), floatArrayOf(72f, 180f, 900f),
        floatArrayOf(28f, 732f, 1500f), floatArrayOf(98f, 806f, 400f), floatArrayOf(192f, 760f, 2100f),
        floatArrayOf(286f, 818f, 1000f), floatArrayOf(364f, 742f, 1700f),
    )
    private val edges = arrayOf(
        intArrayOf(0, 1), intArrayOf(1, 2), intArrayOf(2, 3), intArrayOf(3, 4), intArrayOf(0, 5), intArrayOf(5, 2),
        intArrayOf(6, 7), intArrayOf(7, 8), intArrayOf(8, 9), intArrayOf(9, 10), intArrayOf(6, 8), intArrayOf(8, 10),
    )
    private val signals = arrayOf(
        floatArrayOf(5f, 2f, 3400f, 0f), floatArrayOf(10f, 8f, 4200f, 1100f), floatArrayOf(6f, 8f, 3800f, 2000f),
        floatArrayOf(1f, 3f, 4600f, 700f),
    )

    private fun drawPattern(c: Canvas, t: Float) {
        val fade = ent(t, 100f, 800f)
        stroke.strokeWidth = 1f
        stroke.tint(MINT, 0.16f * fade)
        for ((i, j) in edges) c.drawLine(nodes[i][0], nodes[i][1], nodes[j][0], nodes[j][1], stroke)

        nodes.forEachIndexed { i, (x, y, d) ->
            val a = fade * lerp(0.25f, 0.85f, ping(t, 3200f, d))
            if (i == 2 || i == 8) {
                // Två av punkterna är dokument: ett litet blad med vikt hörn
                stroke.strokeWidth = 1.2f
                stroke.tint(MINT, a)
                path.reset()
                path.moveTo(x - 3.5f, y - 5f); path.lineTo(x + 1.5f, y - 5f); path.lineTo(x + 3.5f, y - 3f)
                path.lineTo(x + 3.5f, y + 5f); path.lineTo(x - 3.5f, y + 5f); path.close()
                c.drawPath(path, stroke)
            } else {
                fill.tint(MINT, a)
                c.drawCircle(x, y, 2.3f, fill)
            }
        }

        if (reduce) return
        for ((from, to, period, delay) in signals) {
            val v = loop(t, period, delay)
            val a = when {
                v < 0.08f -> v / 0.08f
                v > 0.92f -> (1f - v) / 0.08f
                else -> 1f
            }
            val p = nodes[from.toInt()]
            val q = nodes[to.toInt()]
            fill.tint(GOLD, a * fade)
            c.drawCircle(lerp(p[0], q[0], v), lerp(p[1], q[1], v), 3f, fill)
        }
    }

    private fun drawRipples(c: Canvas, t: Float) {
        if (reduce) return
        stroke.strokeWidth = 1.5f
        for ((color, delay) in listOf(GOLD to 0f, MINT to 1700f)) {
            if (t < delay + 900f) continue
            val v = 1f - (1f - loop(t, 3400f, delay + 900f)).pow(2)
            stroke.tint(color, lerp(0.45f, 0f, v))
            c.drawCircle(CX, CY, 58f * lerp(1f, 2.6f, v), stroke)
        }
    }

    private fun drawLogo(c: Canvas, t: Float) {
        val pop = ent(t, 50f, 700f)
        val draw = ent(t, 150f, 1000f)
        val inner = ent(t, 550f, 800f)
        val pen = ent(t, 900f, 900f)

        c.save()
        val sc = lerp(0.82f, 1f, pop)
        c.scale(sc, sc, CX, CY)

        // Guldringen ritar upp sig själv
        stroke.strokeWidth = 3.5f
        stroke.tint(GOLD, pop)
        rect.set(CX - 56f, CY - 56f, CX + 56f, CY + 56f)
        c.drawArc(rect, -90f, 360f * draw, false, stroke)

        // Stort S i Cinzel med ett mjukt guldsken, som i ikonen
        val dy = lerp(8f, 0f, inner)
        text.typeface = cinzel
        text.textSize = 76f
        text.letterSpacing = 0f
        text.getTextBounds("S", 0, 1, bounds)
        val baseline = CY - 6f + dy - bounds.exactCenterY()
        text.tint(GOLD, inner)
        text.setShadowLayer(14f, 0f, 0f, Color.argb((90 * inner).toInt(), 232, 176, 74))
        c.drawText("S", CX, baseline, text)
        text.clearShadowLayer()

        // Signaturdraget skrivs fram under S:et, med en penna som glöder i spetsen
        part.reset()
        val len = swooshMeasure.length * pen
        swooshMeasure.getSegment(0f, len, part, true)
        stroke.strokeWidth = 3f
        stroke.tint(GOLD_PALE, inner)
        c.save(); c.translate(0f, dy)
        c.drawPath(part, stroke)
        if (pen in 0.01f..0.99f) {
            val pos = FloatArray(2)
            swooshMeasure.getPosTan(len, pos, null)
            fill.tint(GOLD_PALE, 0.35f)
            c.drawCircle(pos[0], pos[1], 6f, fill)
            fill.tint(GOLD_PALE, 1f)
            c.drawCircle(pos[0], pos[1], 2.4f, fill)
        }
        c.restore()

        // Två punkter som kretsar på ringen: en mint pennspets och en ljusguld punkt.
        val ang = Math.toRadians((loop(t, 9000f) * 360f - 45f).toDouble())
        val ax = CX + 56f * kotlin.math.sin(ang).toFloat()
        val ay = CY - 56f * kotlin.math.cos(ang).toFloat()
        fill.tint(MINT, 0.18f * inner)
        c.drawCircle(ax, ay, 12f, fill)
        fill.tint(BG, inner)
        nib(ax, ay, 1.45f); c.drawPath(path, fill)
        fill.tint(MINT, inner)
        nib(ax, ay, 1f); c.drawPath(path, fill)

        val bx = 2 * CX - ax
        val by = 2 * CY - ay
        fill.tint(GOLD_PALE, 0.18f * inner)
        c.drawCircle(bx, by, 12f, fill)
        fill.tint(BG, inner)
        c.drawCircle(bx, by, 7.5f, fill)
        fill.tint(GOLD_PALE, inner)
        c.drawCircle(bx, by, 5f, fill)

        c.restore()
    }

    /** Pennspets (romb med spets nedåt), alltid upprätt. */
    private fun nib(x: Float, y: Float, k: Float) {
        path.reset()
        path.moveTo(x, y + 7f * k); path.lineTo(x + 4.6f * k, y - 1f * k); path.lineTo(x, y - 6.5f * k); path.lineTo(x - 4.6f * k, y - 1f * k); path.close()
    }

    private fun drawWordmark(c: Canvas, t: Float) {
        val v = ent(t, 600f, 700f)
        val dy = lerp(16f, 0f, v)

        text.typeface = cinzel
        text.textSize = 42f
        text.letterSpacing = 0.33f
        text.tint(GOLD, v)
        text.setShadowLayer(18f, 0f, 0f, Color.argb((77 * v).toInt(), 232, 176, 74))
        c.drawText("SIGN", CX + 7f, 428f + dy, text)
        text.clearShadowLayer()

        text.typeface = sansBold
        text.textSize = 10.5f
        text.letterSpacing = 0.25f
        text.tint(MINT, v)
        c.drawText(tagline, CX + 1.3f, 456f + dy, text)
    }

    private fun drawConnectors(c: Canvas, t: Float) {
        val fade = ent(t, 1200f, 800f)
        if (fade <= 0f) return
        stroke.strokeWidth = 1.5f
        stroke.tint(LINE, fade)
        c.drawLine(123f, 564f, 159f, 564f, stroke)
        c.drawLine(231f, 564f, 267f, 564f, stroke)
        if (reduce) return
        packet(c, t, 123f, 159f, 0f, GOLD, fade)
        packet(c, t, 231f, 267f, 1100f, MINT, fade)
    }

    private fun packet(c: Canvas, t: Float, x0: Float, x1: Float, delay: Float, color: Int, fade: Float) {
        val v = loop(t, 2200f, delay + 1200f)
        val a = when {
            v < 0.08f -> v / 0.08f
            v > 0.92f -> (1f - v) / 0.08f
            else -> 1f
        }
        val x = lerp(x0, x1, v)
        c.save()
        c.rotate(45f, x, 564f)
        fill.tint(color, a * fade)
        c.drawRect(x - 2.5f, 564f - 2.5f, x + 2.5f, 564f + 2.5f, fill)
        c.restore()
    }

    private fun drawFeature(
        c: Canvas, t: Float, x: Float, label: String, enter: Float, glowAt: Float,
        icon: (Canvas, Float, Float, Float) -> Unit,
    ) {
        val e = ent(t, enter, 700f)
        if (e <= 0f) return
        val lab = ent(t, enter + 400f, 800f)
        val p = pulse(t, glowAt)
        val cx = x + 36f
        val cy = 564f + lerp(16f, 0f, e)

        fill.tint(GOLD, 0.05f * p)
        c.drawCircle(cx, cy, 50f * lerp(0.9f, 1.2f, p), fill)
        fill.tint(GOLD, 0.10f * p)
        c.drawCircle(cx, cy, 42f * lerp(0.95f, 1.15f, p), fill)

        fill.tint(PANEL, e)
        c.drawCircle(cx, cy, 36f, fill)
        stroke.strokeWidth = 1f
        stroke.tint(LINE, e)
        c.drawCircle(cx, cy, 36f, stroke)
        if (p > 0f) {
            stroke.strokeWidth = 1.5f
            stroke.tint(GOLD, p * e)
            c.drawCircle(cx, cy, 36f, stroke)
        }

        c.save()
        c.saveLayerAlpha(cx - 36f, cy - 36f, cx + 36f, cy + 36f, (e * 255).toInt())
        icon(c, t, cx, cy)
        c.restore()
        c.restore()

        text.typeface = sansBold
        text.textSize = 9.5f
        text.letterSpacing = 0.17f
        text.tint(INK, lab)
        c.drawText(label, cx + 0.8f, 624f, text)
    }

    /** Dokument med vikt hörn och textrader; en mint-rad blinkar som markör. */
    private fun docIcon(c: Canvas, t: Float, x: Float, y: Float) {
        stroke.strokeWidth = 2f
        stroke.tint(GOLD, 1f)
        path.reset()
        path.moveTo(x - 11f, y - 15f); path.lineTo(x + 4f, y - 15f); path.lineTo(x + 11f, y - 8f)
        path.lineTo(x + 11f, y + 15f); path.lineTo(x - 11f, y + 15f); path.close()
        path.moveTo(x + 4f, y - 15f); path.lineTo(x + 4f, y - 8f); path.lineTo(x + 11f, y - 8f)
        c.drawPath(path, stroke)
        stroke.strokeWidth = 1.6f
        stroke.tint(GOLD_DIM, 1f)
        c.drawLine(x - 6f, y - 2f, x + 6f, y - 2f, stroke)
        c.drawLine(x - 6f, y + 3f, x + 6f, y + 3f, stroke)
        stroke.tint(MINT, lerp(0.35f, 1f, ping(t, 1400f)))
        c.drawLine(x - 6f, y + 8.5f, x + 2f, y + 8.5f, stroke)
    }

    /** Penna som skriver ett litet drag. */
    private fun penIcon(c: Canvas, t: Float, x: Float, y: Float) {
        val w = ping(t, 1800f)
        c.save()
        c.rotate(-40f + w * 6f, x, y)
        stroke.strokeWidth = 2f
        stroke.tint(GOLD, 1f)
        path.reset()
        path.moveTo(x - 3.5f, y - 15f); path.lineTo(x + 3.5f, y - 15f); path.lineTo(x + 3.5f, y + 6f)
        path.lineTo(x, y + 13f); path.lineTo(x - 3.5f, y + 6f); path.close()
        c.drawPath(path, stroke)
        fill.tint(GOLD_PALE, 1f)
        c.drawCircle(x, y + 13f, 1.6f, fill)
        c.restore()
        stroke.strokeWidth = 1.8f
        stroke.tint(MINT, 1f)
        path.reset()
        path.moveTo(x - 15f, y + 15f)
        path.quadTo(x - 10f, y + 8f + w * 3f, x - 5f, y + 14f)
        path.quadTo(x, y + 19f - w * 3f, x + 6f, y + 13f)
        c.drawPath(path, stroke)
    }

    /** Kamera med ett blänk i objektivet. */
    private fun cameraIcon(c: Canvas, t: Float, x: Float, y: Float) {
        stroke.strokeWidth = 2f
        stroke.tint(GOLD, 1f)
        rect.set(x - 15f, y - 8f, x + 15f, y + 13f)
        c.drawRoundRect(rect, 4f, 4f, stroke)
        path.reset()
        path.moveTo(x - 6f, y - 8f); path.lineTo(x - 3.5f, y - 12.5f); path.lineTo(x + 3.5f, y - 12.5f); path.lineTo(x + 6f, y - 8f)
        c.drawPath(path, stroke)
        stroke.tint(MINT, 1f)
        c.drawCircle(x, y + 2.5f, 6.2f, stroke)
        fill.tint(GOLD_PALE, lerp(0.2f, 1f, ping(t, 2000f)))
        c.drawCircle(x - 2f, y + 0.5f, 1.8f, fill)
    }

    /** Etikett under funktionerna och utvecklaren längst ner. */
    private fun drawFooter(c: Canvas, t: Float) {
        val v = ent(t, 1500f, 800f)

        text.typeface = sansBold
        text.textSize = 10f
        text.letterSpacing = 0.22f
        val w = text.measureText(privacy)
        val iconW = 12f
        val left = CX - (w + iconW + 8f) / 2f

        // Litet hänglås: allt sker i telefonen
        stroke.strokeWidth = 1.5f
        stroke.tint(DIM, v)
        val by = 668f
        rect.set(left, by - 2f, left + 12f, by + 7f)
        c.drawRoundRect(rect, 2f, 2f, stroke)
        rect.set(left + 2.5f, by - 8f, left + 9.5f, by + 2f)
        c.drawArc(rect, 180f, 180f, false, stroke)

        text.textAlign = Paint.Align.LEFT
        text.tint(DIM, v)
        c.drawText(privacy, left + iconW + 8f, by + 3.6f, text)
        text.textAlign = Paint.Align.CENTER

        text.typeface = sansMedium
        text.textSize = 10.5f
        text.letterSpacing = 0.04f
        text.tint(DIM, v * 0.85f)
        c.drawText(developer, CX, 696f, text)
    }
}
