package se.snc.sign

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * En logga som redigeras. Originalet ändras aldrig: beskärning, bakgrundsborttagning och alla penseldrag
 * sparas som steg, så att allt kan ångras, göras om och redigeras igen senare.
 *
 * mask per pixel: 0 = automatiskt, 1 = suddat (genomskinligt), 2 = återställt (originalet).
 */
class LogoEdit(val work: Bitmap) {

    data class Settings(val crop: RectF = RectF(0f, 0f, 1f, 1f), val removeBg: Boolean = true, val strength: Float = 0f, val darken: Boolean = false)

    sealed class Op {
        class Brush(val erase: Boolean, val radius: Float, val pts: ArrayList<Float> = ArrayList()) : Op()
        class Wand(val x: Int, val y: Int, val tolerance: Float, val contiguous: Boolean) : Op()
        class Set(val before: Settings, val after: Settings) : Op()
    }

    val w = work.width
    val h = work.height
    val px = IntArray(w * h).also { work.getPixels(it, 0, w, 0, 0, w, h) }
    val blur: IntArray by lazy { BackgroundRemover.blur(px, w, h) }
    val mask = ByteArray(w * h)

    var settings = Settings()
        private set
    val ops = ArrayList<Op>()
    private val redoStack = ArrayList<Op>()
    val canUndo get() = ops.isNotEmpty()
    val canRedo get() = redoStack.isNotEmpty()

    // Senaste automatiska bakgrundsborttagning (för det beskurna området)
    private var autoKey: Triple<RectF, Boolean, Float>? = null
    private var auto: BackgroundRemover.Result? = null

    /** Det beskurna området i pixlar. */
    fun region(s: Settings = settings): android.graphics.Rect {
        val c = s.crop
        val l = (c.left * w).toInt().coerceIn(0, w - 1); val t = (c.top * h).toInt().coerceIn(0, h - 1)
        val r = (c.right * w).toInt().coerceIn(l + 1, w); val b = (c.bottom * h).toInt().coerceIn(t + 1, h)
        return android.graphics.Rect(l, t, r, b)
    }

    // ---- Steg ----

    fun push(op: Op) {
        ops += op; redoStack.clear()
        apply(op)
    }

    /** Ett penseldrag som redan har ritats in i masken. */
    fun commit(op: Op) { ops += op; redoStack.clear() }

    /** Tillfällig beskärning medan man drar i ramen (blir ett steg när man släpper). */
    fun previewCrop(c: RectF) { settings = settings.copy(crop = RectF(c)) }

    fun change(after: Settings) { if (after != settings) push(Op.Set(settings, after)) }

    fun undo() {
        val op = ops.removeLastOrNull() ?: return
        redoStack += op
        rebuild()
    }

    fun redo() {
        val op = redoStack.removeLastOrNull() ?: return
        ops += op
        apply(op)
    }

    private fun apply(op: Op) {
        when (op) {
            is Op.Brush -> {
                var i = 0
                while (i + 1 < op.pts.size) { dab(op.pts[i], op.pts[i + 1], op.radius, op.erase); i += 2 }
            }
            is Op.Wand -> BackgroundRemover.wand(blur, w, h, op.x, op.y, op.tolerance, op.contiguous, mask, 1)
            is Op.Set -> settings = op.after
        }
    }

    /** Bygger om masken och inställningarna från början (efter ångra). */
    private fun rebuild() {
        mask.fill(0); settings = Settings()
        ops.forEach { apply(it) }
    }

    /** En penselprick: suddar eller återställer en cirkel. Returnerar området som ändrades. */
    fun dab(cx: Float, cy: Float, r: Float, erase: Boolean): android.graphics.Rect {
        val v: Byte = if (erase) 1 else 2
        val x0 = max(0, (cx - r).toInt()); val x1 = min(w - 1, (cx + r).toInt())
        val y0 = max(0, (cy - r).toInt()); val y1 = min(h - 1, (cy + r).toInt())
        val r2 = r * r
        for (y in y0..y1) for (x in x0..x1) {
            val dx = x - cx; val dy = y - cy
            if (dx * dx + dy * dy <= r2) mask[y * w + x] = v
        }
        return android.graphics.Rect(x0, y0, x1 + 1, y1 + 1)
    }

    // ---- Rita resultatet ----

    private fun ensureAuto(rg: android.graphics.Rect) {
        val s = settings
        val key = Triple(RectF(s.crop), s.removeBg, s.strength)
        if (autoKey == key) return
        auto = if (s.removeBg) {
            val sub = IntArray(rg.width() * rg.height())
            for (y in 0 until rg.height()) System.arraycopy(px, (rg.top + y) * w + rg.left, sub, y * rg.width(), rg.width())
            BackgroundRemover.auto(sub, rg.width(), rg.height(), s.strength)
        } else null
        autoKey = key
    }

    /** En pixel i resultatet: färgerna behålls, det som tas bort blir genomskinligt. */
    private fun outPixel(x: Int, y: Int, rg: android.graphics.Rect): Int {
        val i = y * w + x
        val c = px[i]
        val m = mask[i].toInt()
        if (m == 1) return 0
        var out = c or (0xFF shl 24)
        if (m != 2) {
            val a = auto
            if (a != null) {
                val al = a.alpha[(y - rg.top) * rg.width() + (x - rg.left)]
                if (al <= 0f) return 0
                if (al < 1f) {
                    // Ta bort bakgrundens färgstick ur kanten
                    fun un(v: Int, base: Float) = (((v / 255f) - (1 - al) * base) / al).coerceIn(0f, 1f)
                    val r = un((c shr 16) and 255, a.bgR); val g = un((c shr 8) and 255, a.bgG); val b = un(c and 255, a.bgB)
                    out = ((al * 255).toInt() shl 24) or ((r * 255).toInt() shl 16) or ((g * 255).toInt() shl 8) or (b * 255).toInt()
                }
            }
        }
        return if (settings.darken) BackgroundRemover.darken(out) else out
    }

    /** Hela resultatet för det beskurna området. */
    fun render(): Bitmap {
        val rg = region()
        ensureAuto(rg)
        val out = IntArray(rg.width() * rg.height())
        for (y in rg.top until rg.bottom) for (x in rg.left until rg.right) out[(y - rg.top) * rg.width() + (x - rg.left)] = outPixel(x, y, rg)
        return Bitmap.createBitmap(out, rg.width(), rg.height(), Bitmap.Config.ARGB_8888).copy(Bitmap.Config.ARGB_8888, true)
    }

    /** Uppdaterar bara en del av resultatet (snabbt medan man suddar). */
    fun renderPart(target: Bitmap, part: android.graphics.Rect) {
        val rg = region()
        if (target.width != rg.width() || target.height != rg.height()) return
        val r = android.graphics.Rect(part)
        if (!r.intersect(rg)) return
        val row = IntArray(r.width())
        for (y in r.top until r.bottom) {
            for (x in r.left until r.right) row[x - r.left] = outPixel(x, y, rg)
            target.setPixels(row, 0, r.width(), r.left - rg.left, y - rg.top, r.width(), 1)
        }
    }

    /** Det färdiga sigillet: beskuret till innehållet med lite luft runt. */
    fun final(): Bitmap? {
        val b = render()
        val bw = b.width; val bh = b.height
        val p = IntArray(bw * bh); b.getPixels(p, 0, bw, 0, 0, bw, bh)
        var minX = bw; var minY = bh; var maxX = -1; var maxY = -1
        for (y in 0 until bh) for (x in 0 until bw) if ((p[y * bw + x] ushr 24) > 25) {
            if (x < minX) minX = x; if (x > maxX) maxX = x
            if (y < minY) minY = y; if (y > maxY) maxY = y
        }
        if (maxX < 0) return null
        val pad = max(4, (max(maxX - minX, maxY - minY) * 0.02f).toInt())
        minX = max(0, minX - pad); minY = max(0, minY - pad); maxX = min(bw - 1, maxX + pad); maxY = min(bh - 1, maxY + pad)
        return Bitmap.createBitmap(b, minX, minY, maxX - minX + 1, maxY - minY + 1)
    }

    // ---- Spara och läsa in stegen ----

    fun toJson(name: String): JSONObject {
        val arr = JSONArray()
        for (op in ops) arr.put(when (op) {
            is Op.Brush -> JSONObject().put("t", "brush").put("erase", op.erase).put("r", op.radius.toDouble())
                .put("p", JSONArray().apply { op.pts.forEach { put(it.toDouble()) } })
            is Op.Wand -> JSONObject().put("t", "wand").put("x", op.x).put("y", op.y).put("tol", op.tolerance.toDouble()).put("c", op.contiguous)
            is Op.Set -> JSONObject().put("t", "set").put("b", sj(op.before)).put("a", sj(op.after))
        })
        return JSONObject().put("v", 1).put("name", name).put("w", w).put("h", h).put("ops", arr)
    }

    private fun sj(s: Settings) = JSONObject()
        .put("crop", JSONArray().put(s.crop.left.toDouble()).put(s.crop.top.toDouble()).put(s.crop.right.toDouble()).put(s.crop.bottom.toDouble()))
        .put("bg", s.removeBg).put("str", s.strength.toDouble()).put("dark", s.darken)

    private fun js(o: JSONObject): Settings {
        val c = o.optJSONArray("crop")
        val r = if (c != null && c.length() == 4) RectF(c.getDouble(0).toFloat(), c.getDouble(1).toFloat(), c.getDouble(2).toFloat(), c.getDouble(3).toFloat()) else RectF(0f, 0f, 1f, 1f)
        return Settings(r, o.optBoolean("bg", true), o.optDouble("str", 0.0).toFloat(), o.optBoolean("dark", false))
    }

    /** Läser in sparade steg (om bilden har samma storlek). */
    fun load(o: JSONObject) {
        if (o.optInt("w") != w || o.optInt("h") != h) return
        val arr = o.optJSONArray("ops") ?: return
        for (k in 0 until arr.length()) {
            val j = arr.getJSONObject(k)
            val op = when (j.optString("t")) {
                "brush" -> Op.Brush(j.optBoolean("erase"), j.optDouble("r").toFloat(), ArrayList<Float>().apply {
                    val p = j.optJSONArray("p") ?: JSONArray(); for (q in 0 until p.length()) add(p.getDouble(q).toFloat())
                })
                "wand" -> Op.Wand(j.optInt("x"), j.optInt("y"), j.optDouble("tol").toFloat(), j.optBoolean("c", true))
                "set" -> Op.Set(js(j.getJSONObject("b")), js(j.getJSONObject("a")))
                else -> null
            } ?: continue
            ops += op; apply(op)
        }
    }
}

/**
 * Bilden man redigerar. Nyp med två fingrar för att zooma, dra med två fingrar för att flytta.
 * Ett finger: beskär (dra i hörn och kanter), sudda, återställ eller tryck med trollstaven.
 */
class LogoCanvas(context: Context, private val onChanged: () -> Unit) : View(context) {

    enum class Tool { CROP, ERASE, RESTORE, WAND }
    enum class Backdrop { PAPER, DARK, CHECKER }

    var edit: LogoEdit? = null
        set(v) { field = v; fitted = false; invalidate() }
    var result: Bitmap? = null
        set(v) { field = v; invalidate() }
    var tool = Tool.CROP
        set(v) { field = v; invalidate() }
    var backdrop = Backdrop.CHECKER
        set(v) { field = v; invalidate() }
    /** Penselns storlek i skärmpunkter. */
    var brushDp = 24f
    var wandTolerance = 0.25f
    var wandContiguous = true

    private val d = resources.displayMetrics.density
    private val m = Matrix()
    private val inv = Matrix()
    private var fitted = false
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dimPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = 70 }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFFE8B04A.toInt(); strokeWidth = 2f * resources.displayMetrics.density }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE8B04A.toInt() }
    private val brushPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE; strokeWidth = 1.5f * resources.displayMetrics.density }
    private val checker = Paint().apply {
        val s = (8 * resources.displayMetrics.density).toInt().coerceAtLeast(4)
        val b = Bitmap.createBitmap(s * 2, s * 2, Bitmap.Config.ARGB_8888)
        val c = Canvas(b); c.drawColor(0xFFFFFFFF.toInt())
        val p = Paint().apply { color = 0xFFD9D9D9.toInt() }
        c.drawRect(0f, 0f, s.toFloat(), s.toFloat(), p); c.drawRect(s.toFloat(), s.toFloat(), 2f * s, 2f * s, p)
        shader = BitmapShader(b, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }

    private fun fit() {
        val e = edit ?: return
        if (width == 0 || height == 0) return
        val s = min(width * 0.94f / e.w, height * 0.94f / e.h)
        m.reset(); m.postScale(s, s); m.postTranslate((width - e.w * s) / 2f, (height - e.h * s) / 2f)
        fitted = true
    }

    private fun scale(): Float { val v = FloatArray(9); m.getValues(v); return v[Matrix.MSCALE_X] }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { fitted = false }

    override fun onDraw(c: Canvas) {
        val e = edit ?: return
        if (!fitted) fit()
        c.drawColor(0xFF0B1416.toInt())
        c.save()
        c.concat(m)
        val rg = e.region()
        val crop = RectF(rg)
        if (tool == Tool.CROP) c.drawBitmap(e.work, 0f, 0f, dimPaint)
        when (backdrop) {
            Backdrop.PAPER -> c.drawRect(crop, Paint().apply { color = 0xFFFFFFFF.toInt() })
            Backdrop.DARK -> c.drawRect(crop, Paint().apply { color = 0xFF0F1A1C.toInt() })
            Backdrop.CHECKER -> {
                c.save(); val sc = scale(); c.scale(1f / sc, 1f / sc)
                c.drawRect(RectF(crop.left * sc, crop.top * sc, crop.right * sc, crop.bottom * sc), checker)
                c.restore()
            }
        }
        result?.let { c.drawBitmap(it, null, crop, bmpPaint) }
        c.restore()
        if (tool == Tool.CROP) {
            val r = RectF(crop); m.mapRect(r)
            c.drawRect(r, linePaint)
            for ((x, y) in listOf(r.left to r.top, r.right to r.top, r.left to r.bottom, r.right to r.bottom)) c.drawCircle(x, y, 9f * d, handlePaint)
        }
        if ((tool == Tool.ERASE || tool == Tool.RESTORE) && cursor != null) {
            val (x, y) = cursor!!
            c.drawCircle(x, y, brushDp * d / 2f, brushPaint)
        }
    }

    // ---- Beröring ----

    private var cursor: Pair<Float, Float>? = null
    private var stroke: LogoEdit.Op.Brush? = null
    private var multi = false
    private var lastFocus: Pair<Float, Float>? = null
    private var cropHandle = -1 // 0..3 hörn, 4 flytta
    private var cropStart: RectF? = null
    private var downX = 0f; private var downY = 0f

    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(det: ScaleGestureDetector): Boolean {
            val s = scale()
            val f = (det.scaleFactor).coerceIn(0.5f, 2f)
            val target = (s * f).coerceIn(0.2f, 40f)
            m.postScale(target / s, target / s, det.focusX, det.focusY)
            invalidate(); return true
        }
    })

    private fun toWork(x: Float, y: Float): FloatArray {
        m.invert(inv); val p = floatArrayOf(x, y); inv.mapPoints(p); return p
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val e = edit ?: return false
        parent?.requestDisallowInterceptTouchEvent(true)
        scaler.onTouchEvent(ev)
        if (ev.pointerCount >= 2) {
            if (!multi) {
                multi = true
                // Ett påbörjat drag med ett finger avbryts när andra fingret kommer
                stroke?.let { s -> stroke = null; if (s.pts.isNotEmpty()) { e.push(s); e.undo(); onChanged() } }
                cropStart?.let { e.previewCrop(it); onChanged() }
                cropHandle = -1; cropStart = null; previewCrop = null; cursor = null
            }
            val fx = (ev.getX(0) + ev.getX(1)) / 2f; val fy = (ev.getY(0) + ev.getY(1)) / 2f
            lastFocus?.let { (lx, ly) -> m.postTranslate(fx - lx, fy - ly) }
            lastFocus = fx to fy
            invalidate()
            if (ev.actionMasked == MotionEvent.ACTION_POINTER_UP) lastFocus = null
            return true
        }
        if (multi) {
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) { multi = false; lastFocus = null }
            return true
        }
        val p = toWork(ev.x, ev.y)
        when (tool) {
            Tool.CROP -> cropTouch(ev, p, e)
            Tool.ERASE, Tool.RESTORE -> brushTouch(ev, p, e)
            Tool.WAND -> if (ev.actionMasked == MotionEvent.ACTION_DOWN) { downX = ev.x; downY = ev.y }
                else if (ev.actionMasked == MotionEvent.ACTION_UP && hypot(ev.x - downX, ev.y - downY) < 12 * d) {
                    val rg = e.region()
                    val x = p[0].toInt(); val y = p[1].toInt()
                    if (rg.contains(x, y)) { e.push(LogoEdit.Op.Wand(x, y, wandTolerance, wandContiguous)); onChanged() }
                }
        }
        return true
    }

    private fun brushTouch(ev: MotionEvent, p: FloatArray, e: LogoEdit) {
        val r = brushDp * d / 2f / scale()
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                stroke = LogoEdit.Op.Brush(tool == Tool.ERASE, r)
                addDab(p, e)
            }
            MotionEvent.ACTION_MOVE -> addDab(p, e)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                stroke?.let { s -> stroke = null; e.commit(s); onChanged() }
                cursor = null
            }
        }
        if (ev.actionMasked != MotionEvent.ACTION_UP) cursor = ev.x to ev.y
        invalidate()
    }

    private fun addDab(p: FloatArray, e: LogoEdit) {
        val s = stroke ?: return
        // Fyll i mellan punkterna så att snabba drag blir hela
        val lastX = s.pts.getOrNull(s.pts.size - 2); val lastY = s.pts.getOrNull(s.pts.size - 1)
        val steps = if (lastX == null || lastY == null) 1 else max(1, (hypot(p[0] - lastX, p[1] - lastY) / (s.radius * 0.4f)).toInt())
        for (k in 1..steps) {
            val x = if (lastX == null) p[0] else lastX + (p[0] - lastX) * k / steps
            val y = if (lastY == null) p[1] else lastY + (p[1] - lastY) * k / steps
            s.pts.add(x); s.pts.add(y)
            val part = e.dab(x, y, s.radius, s.erase)
            result?.let { e.renderPart(it, part) }
        }
    }

    private fun cropTouch(ev: MotionEvent, p: FloatArray, e: LogoEdit) {
        val rg = RectF(e.region())
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val tol = 28 * d / scale()
                val corners = listOf(rg.left to rg.top, rg.right to rg.top, rg.left to rg.bottom, rg.right to rg.bottom)
                cropHandle = corners.indexOfFirst { (x, y) -> hypot(p[0] - x, p[1] - y) < tol }
                if (cropHandle < 0 && rg.contains(p[0], p[1])) cropHandle = 4
                cropStart = RectF(e.settings.crop)
                downX = p[0]; downY = p[1]
            }
            MotionEvent.ACTION_MOVE -> {
                val start = cropStart ?: return
                if (cropHandle < 0) return
                val nx = (p[0] / e.w).coerceIn(0f, 1f); val ny = (p[1] / e.h).coerceIn(0f, 1f)
                val c = RectF(start)
                val minF = 0.05f
                when (cropHandle) {
                    0 -> { c.left = min(nx, c.right - minF); c.top = min(ny, c.bottom - minF) }
                    1 -> { c.right = max(nx, c.left + minF); c.top = min(ny, c.bottom - minF) }
                    2 -> { c.left = min(nx, c.right - minF); c.bottom = max(ny, c.top + minF) }
                    3 -> { c.right = max(nx, c.left + minF); c.bottom = max(ny, c.top + minF) }
                    4 -> {
                        val dx = ((p[0] - downX) / e.w).coerceIn(-start.left, 1f - start.right)
                        val dy = ((p[1] - downY) / e.h).coerceIn(-start.top, 1f - start.bottom)
                        c.offset(dx, dy)
                    }
                }
                previewCrop = c
                cropPreview(e, c)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val c = previewCrop
                if (c != null && cropStart != null) {
                    // Återställ och lägg ändringen som ett steg (så att den kan ångras)
                    cropPreview(e, cropStart!!)
                    e.change(e.settings.copy(crop = c))
                    onChanged()
                }
                previewCrop = null; cropStart = null; cropHandle = -1
            }
        }
        invalidate()
    }

    private var previewCrop: RectF? = null

    /** Visar ramen medan man drar (utan att räkna om bilden förrän man släpper). */
    private fun cropPreview(e: LogoEdit, c: RectF) {
        e.previewCrop(c)
        result = null
    }
}
