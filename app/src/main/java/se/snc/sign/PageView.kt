package se.snc.sign

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.core.content.res.ResourcesCompat
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * En dokumentsida med dess fält. Fält flyttas genom att dra, storleken ändras i det blå hörnet,
 * det röda krysset tar bort fältet, stämplar vrids med guldhandtaget ovanför
 * och ett tryck på ett osignerat fält öppnar signeringen.
 * I läget readOnly (förhandsvisning) visas bara sidan, och fält som saknar signatur visar vem de väntar på.
 */
@SuppressLint("ViewConstructor")
class PageView(context: Context, private val host: Host) : View(context) {

    interface Host {
        val fields: List<Field>
        val placing: Boolean
        val readOnly: Boolean get() = false
        fun place(page: Int, fx: Float, fy: Float) {}
        fun sign(field: Field) {}
        fun remove(field: Field) {}
        fun changed(field: Field) {}
        /** En stämpel har valts (tryck) eller vrids (handtaget). */
        fun selected(field: Field) {}
        fun rotating(field: Field) {}
    }

    var page = 0
    var aspect = 1.414f
    var bitmap: Bitmap? = null
        set(v) { field = v; invalidate() }
    /** Stämpeln som är vald för vridning (visar handtaget). */
    var selectedId: Long = -1L
        set(v) { field = v; invalidate() }

    private val d = resources.displayMetrics.density
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val boxFill = Paint().apply { color = Color.argb(22, 47, 107, 255) }
    private val boxLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2 * d; color = Color.rgb(47, 107, 255)
        pathEffect = DashPathEffect(floatArrayOf(6 * d, 4 * d), 0f)
    }
    private val waitFill = Paint().apply { color = Color.argb(18, 232, 119, 109) }
    private val waitLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2 * d; color = Color.rgb(232, 119, 109)
        pathEffect = DashPathEffect(floatArrayOf(6 * d, 4 * d), 0f)
    }
    private val selLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f * d; color = Color.rgb(47, 107, 255)
        pathEffect = DashPathEffect(floatArrayOf(5 * d, 4 * d), 0f)
    }
    private val placeLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2 * d; color = 0xB3E8B04A.toInt()
        pathEffect = DashPathEffect(floatArrayOf(8 * d, 6 * d), 0f)
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(47, 107, 255); textAlign = Paint.Align.CENTER
        typeface = ResourcesCompat.getFont(context, R.font.jakarta_bold) ?: Typeface.DEFAULT_BOLD
    }
    private val red = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(229, 72, 77) }
    private val blue = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(47, 107, 255) }
    private val gold = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE8B04A.toInt(); strokeWidth = 1.5f * d }
    private val dark = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0F1A1C.toInt(); strokeWidth = 2f * d; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeWidth = 2.2f * d; strokeCap = Paint.Cap.ROUND }
    private val previews = HashMap<Long, Pair<String, Bitmap>>()
    private val tapText = context.getString(R.string.tap_to_sign)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (w * aspect).toInt())
    }

    private fun rect(f: Field) = RectF(f.x * width, f.y * height, (f.x + f.w) * width, (f.y + f.h) * height)
    private val handleGap get() = 26 * d

    override fun onDraw(c: Canvas) {
        c.drawColor(Color.WHITE)
        bitmap?.let { c.drawBitmap(it, null, RectF(0f, 0f, width.toFloat(), height.toFloat()), bmpPaint) }
        if (host.placing) c.drawRect(3 * d, 3 * d, width - 3 * d, height - 3 * d, placeLine)
        for (f in host.fields) {
            if (f.page != page) continue
            val r = rect(f)
            c.save()
            c.rotate(f.rot, r.centerX(), r.centerY())
            if (f.done) {
                preview(f, r)?.let { c.drawBitmap(it, null, r, bmpPaint) }
            } else if (host.readOnly) {
                c.drawRoundRect(r, 6 * d, 6 * d, waitFill)
                c.drawRoundRect(r, 6 * d, 6 * d, waitLine)
                label.color = Color.rgb(232, 119, 109)
                label.textSize = (r.height() * 0.22f).coerceIn(8 * d, 13 * d)
                val who = f.signer.ifBlank { "…" }
                c.drawText(context.tr(R.string.waiting_for, who), r.centerX(), r.centerY() - (label.ascent() + label.descent()) / 2f, label)
                label.color = Color.rgb(47, 107, 255)
            } else {
                c.drawRoundRect(r, 6 * d, 6 * d, boxFill)
                c.drawRoundRect(r, 6 * d, 6 * d, boxLine)
                label.textSize = (r.height() * 0.28f).coerceIn(10 * d, 15 * d)
                c.drawText(tapText, r.centerX(), r.centerY() - (label.ascent() + label.descent()) / 2f, label)
            }
            if (!host.readOnly) {
                if (f.type == Field.Type.STAMP && f.id == selectedId) {
                    c.drawRect(r, selLine)
                    // Vridhandtag ovanför mitten
                    val hx = r.centerX(); val hy = r.top - handleGap
                    c.drawLine(hx, r.top, hx, hy, gold)
                    c.drawCircle(hx, hy, 12 * d, gold)
                    val a = 5.5f * d
                    c.drawArc(RectF(hx - a, hy - a, hx + a, hy + a), -60f, 280f, false, dark)
                }
                // Ta bort (rött kryss) och ändra storlek (blå punkt)
                val k = 12 * d
                c.drawCircle(r.right, r.top, k, red)
                val q = 4.5f * d
                c.drawLine(r.right - q, r.top - q, r.right + q, r.top + q, white)
                c.drawLine(r.right - q, r.top + q, r.right + q, r.top - q, white)
                c.drawCircle(r.right, r.bottom, 10 * d, white)
                c.drawCircle(r.right, r.bottom, 7.5f * d, blue)
            }
            c.restore()
        }
    }

    private fun preview(f: Field, r: RectF): Bitmap? {
        val key = "${f.type}|${f.sigFile}|${f.stampId}|${f.name}|${f.date}|${r.width().toInt()}x${r.height().toInt()}"
        previews[f.id]?.let { if (it.first == key) return it.second }
        val b = Composer.compose(context, f, r.width().toInt(), r.height().toInt()) ?: return null
        previews[f.id] = key to b
        return b
    }

    /** Pekpunkten uttryckt i fältets ovridna koordinater. */
    private fun local(f: Field, x: Float, y: Float): FloatArray {
        val r = rect(f)
        val a = Math.toRadians(-f.rot.toDouble())
        val dx = x - r.centerX(); val dy = y - r.centerY()
        return floatArrayOf(
            (r.centerX() + dx * cos(a) - dy * sin(a)).toFloat(),
            (r.centerY() + dx * sin(a) + dy * cos(a)).toFloat(),
        )
    }

    // ---- Beröring ----
    private enum class Mode { NONE, MOVE, RESIZE, DELETE, PLACE, ROTATE }
    private var mode = Mode.NONE
    private var target: Field? = null
    private var downX = 0f; private var downY = 0f
    private var start = FloatArray(5)
    private var moved = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (host.readOnly) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; moved = false; mode = Mode.NONE; target = null
                val hitR = 20 * d
                for (f in host.fields.asReversed()) {
                    if (f.page != page) continue
                    val r = rect(f)
                    val (lx, ly) = local(f, e.x, e.y).let { it[0] to it[1] }
                    when {
                        f.type == Field.Type.STAMP && f.id == selectedId && near(lx, ly, r.centerX(), r.top - handleGap, hitR + 4 * d) -> mode = Mode.ROTATE
                        near(lx, ly, r.right, r.top, hitR) -> mode = Mode.DELETE
                        near(lx, ly, r.right, r.bottom, hitR) -> mode = Mode.RESIZE
                        r.contains(lx, ly) -> mode = Mode.MOVE
                        else -> continue
                    }
                    target = f
                    start = floatArrayOf(f.x, f.y, f.w, f.h, f.rot)
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
                if (host.placing) { mode = Mode.PLACE; return true }
                if (selectedId != -1L) { selectedId = -1L; host.selected(Field(-1, Field.Type.STAMP, page, 0f, 0f, 0f, 0f)) }
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!moved && (abs(e.x - downX) > slop || abs(e.y - downY) > slop)) moved = true
                val f = target ?: return true
                if (!moved) return true
                val dx = (e.x - downX) / width; val dy = (e.y - downY) / height
                when (mode) {
                    Mode.MOVE -> {
                        f.x = (start[0] + dx).coerceIn(0f, 1f - f.w)
                        f.y = (start[1] + dy).coerceIn(0f, 1f - f.h)
                    }
                    Mode.RESIZE -> {
                        val keep = f.type != Field.Type.SIGN
                        val ratio = start[3] / start[2]
                        f.w = (start[2] + dx).coerceIn(0.06f, 1f - f.x)
                        f.h = if (keep) f.w * ratio else (start[3] + dy).coerceIn(0.025f, 1f - f.y)
                        if (f.y + f.h > 1f) { f.h = 1f - f.y; if (keep) f.w = f.h / ratio }
                    }
                    Mode.ROTATE -> {
                        val r = rect(f)
                        val ang = Math.toDegrees(atan2((e.x - r.centerX()).toDouble(), -(e.y - r.centerY()).toDouble())).toFloat()
                        f.rot = Math.round(ang).toFloat().coerceIn(-180f, 180f)
                        host.rotating(f)
                    }
                    else -> {}
                }
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                val f = target
                when {
                    mode == Mode.PLACE && !moved -> host.place(page, e.x / width, e.y / height)
                    f != null && moved && (mode == Mode.MOVE || mode == Mode.RESIZE || mode == Mode.ROTATE) -> host.changed(f)
                    f != null && !moved && mode == Mode.DELETE -> { previews.remove(f.id); host.remove(f) }
                    f != null && !moved && f.type == Field.Type.SIGN && !f.done -> host.sign(f)
                    f != null && !moved && f.type == Field.Type.SIGN -> host.sign(f)
                    f != null && !moved && f.type == Field.Type.STAMP -> { selectedId = f.id; host.selected(f) }
                }
                mode = Mode.NONE; target = null
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { mode = Mode.NONE; target = null }
        }
        return true
    }

    private fun near(x: Float, y: Float, px: Float, py: Float, r: Float) = abs(x - px) < r && abs(y - py) < r
}
