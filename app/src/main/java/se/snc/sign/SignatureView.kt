package se.snc.sign

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Ytan man signerar på. Mjuka penndrag där snabba drag blir tunnare, som med en riktig penna.
 * - Ångra och Gör om i många steg (även Rensa kan ångras).
 * - Dragen kan sparas och öppnas igen, så att man kan fortsätta eller ändra t.ex. bara efternamnet.
 * - En fotad eller infogad signatur kan ligga som grund och kompletteras.
 */
class SignatureView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    /** Punkter sparas som andel av ytan (0–1) så att de passar även om ytan får en annan storlek. */
    private class Pt(val x: Float, val y: Float, val t: Long, val p: Float)

    private sealed class Op {
        class Add(val stroke: ArrayList<Pt>) : Op()
        class Clear(val strokes: List<ArrayList<Pt>>, val base: Bitmap?) : Op()
    }

    private val strokes = ArrayList<ArrayList<Pt>>()
    private val undoStack = ArrayList<Op>()
    private val redoStack = ArrayList<Op>()
    private var current: ArrayList<Pt>? = null

    /** Grund: en fotad eller infogad signatur som man ritar vidare på. */
    var base: Bitmap? = null
        set(v) { field = v; invalidate(); onChange() }

    var ink: Int = Ink.BLUE
        set(v) { field = v; invalidate() }
    var onChange: () -> Unit = {}

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    private val thickness get() = max(3f, min(width, height) * 0.012f)
    val isEmpty get() = strokes.isEmpty() && base == null
    val canUndo get() = undoStack.isNotEmpty()
    val canRedo get() = redoStack.isNotEmpty()
    val hasStrokes get() = strokes.isNotEmpty()

    fun clear() {
        if (isEmpty) return
        undoStack += Op.Clear(ArrayList(strokes), base)
        redoStack.clear()
        strokes.clear(); base = null
        invalidate(); onChange()
    }

    fun undo() {
        val op = undoStack.removeLastOrNull() ?: return
        when (op) {
            is Op.Add -> strokes.remove(op.stroke)
            is Op.Clear -> { strokes.addAll(op.strokes); base = op.base }
        }
        redoStack += op
        invalidate(); onChange()
    }

    fun redo() {
        val op = redoStack.removeLastOrNull() ?: return
        when (op) {
            is Op.Add -> strokes.add(op.stroke)
            is Op.Clear -> { strokes.clear(); base = null }
        }
        undoStack += op
        invalidate(); onChange()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        val w = width.toFloat().coerceAtLeast(1f); val h = height.toFloat().coerceAtLeast(1f)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val s = arrayListOf(pt(e.x / w, e.y / h, e.eventTime, e.pressure, e.getToolType(0)))
                current = s
                strokes.add(s)
            }
            MotionEvent.ACTION_MOVE -> current?.let { s ->
                for (i in 0 until e.historySize) s.add(pt(e.getHistoricalX(i) / w, e.getHistoricalY(i) / h, e.getHistoricalEventTime(i), e.getHistoricalPressure(i), e.getToolType(0)))
                s.add(pt(e.x / w, e.y / h, e.eventTime, e.pressure, e.getToolType(0)))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> current?.let { s ->
                current = null
                undoStack += Op.Add(s)
                redoStack.clear()
                onChange()
            }
        }
        invalidate()
        return true
    }

    private fun pt(x: Float, y: Float, t: Long, pressure: Float, tool: Int) =
        Pt(x, y, t, if (tool == MotionEvent.TOOL_TYPE_STYLUS) pressure.coerceIn(0.1f, 1f) else 0.5f)

    /** Var grunden ritas: centrerad i ytan, högst 90 % av bredden och 70 % av höjden. */
    private fun baseRect(): RectF? {
        val b = base ?: return null
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return null
        val s = min(w * 0.9f / b.width, h * 0.7f / b.height)
        val bw = b.width * s; val bh = b.height * s
        return RectF((w - bw) / 2f, (h - bh) / 2f, (w + bw) / 2f, (h + bh) / 2f)
    }

    override fun onDraw(canvas: Canvas) {
        baseRect()?.let { canvas.drawBitmap(base!!, null, it, bmpPaint) }
        for (s in strokes) drawStroke(canvas, s, thickness, width.toFloat(), height.toFloat(), 0f, 0f)
    }

    private fun drawStroke(c: Canvas, pts: List<Pt>, base: Float, w: Float, h: Float, dx: Float, dy: Float) {
        paint.color = ink
        fun X(p: Pt) = p.x * w - dx
        fun Y(p: Pt) = p.y * h - dy
        if (pts.size == 1) {
            paint.style = Paint.Style.FILL
            c.drawCircle(X(pts[0]), Y(pts[0]), base * 0.6f, paint)
            paint.style = Paint.Style.STROKE
            return
        }
        var prevW = base
        for (i in 1 until pts.size) {
            val a = pts[i - 1]; val b = pts[i]
            val dist = hypot(X(b) - X(a), Y(b) - Y(a))
            val dt = max(1L, b.t - a.t).toFloat()
            val v = dist / dt
            val target = base * (0.55f + 0.9f * b.p) * (1.3f - v * 0.35f).coerceIn(0.45f, 1.25f)
            val sw = prevW * 0.7f + target * 0.3f
            prevW = sw
            paint.strokeWidth = sw
            if (i >= 2) {
                val z = pts[i - 2]
                val path = android.graphics.Path().apply {
                    moveTo((X(z) + X(a)) / 2f, (Y(z) + Y(a)) / 2f)
                    quadTo(X(a), Y(a), (X(a) + X(b)) / 2f, (Y(a) + Y(b)) / 2f)
                }
                c.drawPath(path, paint)
            } else {
                c.drawLine(X(a), Y(a), (X(a) + X(b)) / 2f, (Y(a) + Y(b)) / 2f, paint)
            }
        }
        val l = pts.last(); val p = pts[pts.size - 2]
        c.drawLine((X(p) + X(l)) / 2f, (Y(p) + Y(l)) / 2f, X(l), Y(l), paint)
    }

    /** Signaturen (grund + drag) beskuren till sina gränser, med genomskinlig bakgrund, i dubbel upplösning. */
    fun toTrimmedBitmap(scale: Float = 2f): Bitmap? {
        if (isEmpty) return null
        val w = width.toFloat(); val h = height.toFloat()
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (s in strokes) for (p in s) { minX = min(minX, p.x * w); minY = min(minY, p.y * h); maxX = max(maxX, p.x * w); maxY = max(maxY, p.y * h) }
        val br = baseRect()
        if (br != null) { minX = min(minX, br.left); minY = min(minY, br.top); maxX = max(maxX, br.right); maxY = max(maxY, br.bottom) }
        val pad = thickness * 2f
        minX -= pad; minY -= pad; maxX += pad; maxY += pad
        val ow = ((maxX - minX) * scale).toInt().coerceAtLeast(1)
        val oh = ((maxY - minY) * scale).toInt().coerceAtLeast(1)
        val out = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.scale(scale, scale)
        if (br != null) c.drawBitmap(base!!, null, RectF(br.left - minX, br.top - minY, br.right - minX, br.bottom - minY), bmpPaint)
        for (s in strokes) drawStroke(c, s, thickness, w, h, minX, minY)
        return out
    }

    // ---- Spara och öppna dragen ----

    fun saveStrokes(f: File) {
        val arr = JSONArray()
        for (s in strokes) {
            val pts = JSONArray()
            for (p in s) pts.put(JSONArray().put(p.x.toDouble()).put(p.y.toDouble()).put(p.t).put(p.p.toDouble()))
            arr.put(pts)
        }
        f.writeText(JSONObject().put("v", 1).put("ink", ink).put("strokes", arr).toString())
    }

    /** Läser in sparade drag. De kan ångras en och en, som om de just ritats. */
    fun loadStrokes(f: File) {
        val o = runCatching { JSONObject(f.readText()) }.getOrNull() ?: return
        val arr = o.optJSONArray("strokes") ?: return
        if (o.has("ink")) ink = o.getInt("ink")
        for (i in 0 until arr.length()) {
            val pts = arr.getJSONArray(i)
            val s = ArrayList<Pt>()
            for (j in 0 until pts.length()) {
                val p = pts.getJSONArray(j)
                s.add(Pt(p.getDouble(0).toFloat(), p.getDouble(1).toFloat(), p.getLong(2), p.getDouble(3).toFloat()))
            }
            if (s.isNotEmpty()) { strokes.add(s); undoStack += Op.Add(s) }
        }
        invalidate(); onChange()
    }
}
