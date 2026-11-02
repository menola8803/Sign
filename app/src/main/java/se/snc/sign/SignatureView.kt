package se.snc.sign

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Ytan man signerar på. Mjuka penndrag där snabba drag blir tunnare, som med en riktig penna. */
class SignatureView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private class Pt(val x: Float, val y: Float, val t: Long, val p: Float)

    private val strokes = ArrayList<ArrayList<Pt>>()
    private var current: ArrayList<Pt>? = null
    var ink: Int = Ink.BLUE
        set(v) { field = v; invalidate() }
    var onChange: () -> Unit = {}

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val base get() = max(3f, min(width, height) * 0.012f)
    val isEmpty get() = strokes.isEmpty()

    fun clear() { strokes.clear(); invalidate(); onChange() }
    fun undo() { if (strokes.isNotEmpty()) strokes.removeAt(strokes.size - 1); invalidate(); onChange() }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                current = arrayListOf(pt(e.x, e.y, e.eventTime, e.pressure, e.getToolType(0)))
                strokes.add(current!!)
            }
            MotionEvent.ACTION_MOVE -> current?.let { s ->
                for (h in 0 until e.historySize) s.add(pt(e.getHistoricalX(h), e.getHistoricalY(h), e.getHistoricalEventTime(h), e.getHistoricalPressure(h), e.getToolType(0)))
                s.add(pt(e.x, e.y, e.eventTime, e.pressure, e.getToolType(0)))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { current = null; onChange() }
        }
        invalidate()
        return true
    }

    private fun pt(x: Float, y: Float, t: Long, pressure: Float, tool: Int) =
        Pt(x, y, t, if (tool == MotionEvent.TOOL_TYPE_STYLUS) pressure.coerceIn(0.1f, 1f) else 0.5f)

    override fun onDraw(canvas: Canvas) {
        for (s in strokes) drawStroke(canvas, s, base, 0f, 0f)
    }

    private fun drawStroke(c: Canvas, pts: List<Pt>, base: Float, dx: Float, dy: Float) {
        paint.color = ink
        if (pts.size == 1) {
            paint.style = Paint.Style.FILL
            c.drawCircle(pts[0].x - dx, pts[0].y - dy, base * 0.6f, paint)
            paint.style = Paint.Style.STROKE
            return
        }
        var prevW = base
        for (i in 1 until pts.size) {
            val a = pts[i - 1]; val b = pts[i]
            val dist = hypot(b.x - a.x, b.y - a.y)
            val dt = max(1L, b.t - a.t).toFloat()
            val v = dist / dt
            val target = base * (0.55f + 0.9f * b.p) * (1.3f - v * 0.35f).coerceIn(0.45f, 1.25f)
            val w = prevW * 0.7f + target * 0.3f
            prevW = w
            paint.strokeWidth = w
            if (i >= 2) {
                val z = pts[i - 2]
                val path = android.graphics.Path().apply {
                    moveTo((z.x + a.x) / 2f - dx, (z.y + a.y) / 2f - dy)
                    quadTo(a.x - dx, a.y - dy, (a.x + b.x) / 2f - dx, (a.y + b.y) / 2f - dy)
                }
                c.drawPath(path, paint)
            } else {
                c.drawLine(a.x - dx, a.y - dy, (a.x + b.x) / 2f - dx, (a.y + b.y) / 2f - dy, paint)
            }
        }
        val l = pts.last(); val p = pts[pts.size - 2]
        c.drawLine((p.x + l.x) / 2f - dx, (p.y + l.y) / 2f - dy, l.x - dx, l.y - dy, paint)
    }

    /** Signaturen beskuren till sina gränser, med genomskinlig bakgrund, i dubbel upplösning. */
    fun toTrimmedBitmap(scale: Float = 2f): Bitmap? {
        if (strokes.isEmpty()) return null
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (s in strokes) for (p in s) { minX = min(minX, p.x); minY = min(minY, p.y); maxX = max(maxX, p.x); maxY = max(maxY, p.y) }
        val pad = base * 2f
        minX -= pad; minY -= pad; maxX += pad; maxY += pad
        val w = ((maxX - minX) * scale).toInt().coerceAtLeast(1)
        val h = ((maxY - minY) * scale).toInt().coerceAtLeast(1)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.scale(scale, scale)
        for (s in strokes) drawStroke(c, s, base, minX, minY)
        return out
    }
}
