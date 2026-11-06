package se.snc.sign

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * Sparade dokument. Varje dokument har en egen mapp:
 *   source.<ext>   originalet
 *   pages/         sidbilder (för förhandsvisning och Word/ODT/DOC)
 *   assets/        kopior av signaturer och foton som använts (ändras aldrig i efterhand)
 *   signed.<ext>   senaste signerade fil
 *   thumb.jpg      miniatyr av första sidan med signaturerna
 *   meta.json      namn, format, tider, sidstorlekar och fält
 * Dokument som öppnats men inte gjorts klara sparas också, så att man kan fortsätta senare.
 */
class LibDoc(val dir: File) {

    val id: String get() = dir.name
    private val metaFile get() = File(dir, "meta.json")
    private var meta: JSONObject = runCatching { JSONObject(metaFile.readText()) }.getOrDefault(JSONObject())

    var name: String
        get() = meta.optString("name", "document")
        set(v) { meta.put("name", v) }
    var format: DocFormat
        get() = runCatching { DocFormat.valueOf(meta.optString("format")) }.getOrDefault(DocFormat.PDF)
        set(v) { meta.put("format", v.name) }
    val created get() = meta.optLong("created")
    val updated get() = meta.optLong("updated")
    val signedExt: String? get() = meta.optString("signedExt").ifBlank { null }

    val source get() = File(dir, "source." + format.ext)
    val pagesDir get() = File(dir, "pages").apply { mkdirs() }
    val assetsDir get() = File(dir, "assets").apply { mkdirs() }
    val thumb get() = File(dir, "thumb.jpg")
    val signedFile: File? get() = signedExt?.let { File(dir, "signed.$it") }?.takeIf { it.exists() }

    fun pageFile(i: Int) = File(pagesDir, "page-%03d.jpg".format(i))

    var pageSizes: List<Pair<Float, Float>>
        get() {
            val a = meta.optJSONArray("sizes") ?: return emptyList()
            return (0 until a.length()).map { a.getJSONArray(it).let { p -> p.getDouble(0).toFloat() to p.getDouble(1).toFloat() } }
        }
        set(v) { meta.put("sizes", JSONArray().apply { v.forEach { put(JSONArray().put(it.first.toDouble()).put(it.second.toDouble())) } }) }

    var anchors: List<Int>
        get() { val a = meta.optJSONArray("anchors") ?: return emptyList(); return (0 until a.length()).map { a.getInt(it) } }
        set(v) { meta.put("anchors", JSONArray().apply { v.forEach { put(it) } }) }

    var fields: List<Field>
        get() { val a = meta.optJSONArray("fields") ?: return emptyList(); return (0 until a.length()).map { Field.fromJson(a.getJSONObject(it)) } }
        set(v) { meta.put("fields", JSONArray().apply { v.forEach { put(it.toJson()) } }) }

    val pageCount get() = pageSizes.size

    // ---- Status ----
    enum class Status { SIGNED, PARTLY, NOT_SIGNED }

    val signTotal get() = fields.count { it.type == Field.Type.SIGN }
    val signDone get() = fields.count { it.type == Field.Type.SIGN && it.done }
    val status: Status
        get() = when {
            signTotal == 0 || signDone == 0 -> Status.NOT_SIGNED
            signDone == signTotal -> Status.SIGNED
            else -> Status.PARTLY
        }
    /** Namnen på de som har signerat, och de som saknas. */
    val signedBy get() = fields.filter { it.type == Field.Type.SIGN && it.done }.map { it.name.ifBlank { it.signer } }.filter { it.isNotBlank() }.distinct()
    val waitingFor get() = fields.filter { it.type == Field.Type.SIGN && !it.done }.map { it.signer }.filter { it.isNotBlank() }.distinct()

    fun save() {
        meta.put("updated", System.currentTimeMillis())
        if (!meta.has("created")) meta.put("created", System.currentTimeMillis())
        metaFile.writeText(meta.toString())
    }

    fun setSigned(file: File, ext: String) {
        dir.listFiles()?.filter { it.name.startsWith("signed.") }?.forEach { it.delete() }
        file.copyTo(File(dir, "signed.$ext"), overwrite = true)
        meta.put("signedExt", ext)
        save()
    }

    /** Kopierar en signatur eller ett foto in i dokumentets mapp; returnerar sökvägen till kopian. */
    fun asset(src: File, prefix: String): String {
        val f = File(assetsDir, "${prefix}_${System.nanoTime()}.${src.extension.ifBlank { "png" }}")
        src.copyTo(f, overwrite = true)
        return f.path
    }

    fun delete() = dir.deleteRecursively()

    /** Byter namn på dokumentet (filnamnet när det delas). */
    fun rename(newName: String) {
        name = newName.trim().ifBlank { name }
        save()
    }

    /**
     * Sparar en kopia med nytt namn, så att originalet och tidigare signerade versioner finns kvar.
     * Signaturer och foton följer med (sökvägarna pekar om till kopians mapp).
     */
    fun copyAs(ctx: Context, newName: String): LibDoc {
        val dir2 = File(root(ctx), newId()).apply { mkdirs() }
        dir.copyRecursively(dir2, overwrite = true)
        val c = LibDoc(dir2)
        val old = dir.path
        c.fields = c.fields.onEach { f -> f.sigFile?.let { p -> if (p.startsWith(old)) f.sigFile = dir2.path + p.removePrefix(old) } }
        c.name = newName.trim().ifBlank { name }
        c.meta.put("created", System.currentTimeMillis())
        c.save()
        return c
    }

    /** Ritar första sidan med alla fält som miniatyr. */
    fun makeThumb(ctx: Context, pageBitmap: Bitmap? = null) {
        val sizes = pageSizes
        if (sizes.isEmpty()) return
        val src = pageBitmap ?: BitmapFactory.decodeFile(pageFile(0).path) ?: return
        val w = 420
        val h = (w * sizes[0].second / sizes[0].first).toInt().coerceAtLeast(1)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.WHITE)
        c.drawBitmap(src, null, RectF(0f, 0f, w.toFloat(), h.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
        drawFields(ctx, c, fields.filter { it.page == 0 }, w.toFloat(), h.toFloat())
        FileOutputStream(thumb).use { out.compress(Bitmap.CompressFormat.JPEG, 85, it) }
    }

    companion object {
        fun root(ctx: Context) = File(ctx.filesDir, "library").apply { mkdirs() }

        private fun newId() = System.currentTimeMillis().toString(36) + (1000..9999).random()

        fun all(ctx: Context): List<LibDoc> =
            (root(ctx).listFiles()?.filter { File(it, "meta.json").exists() } ?: emptyList())
                .map { LibDoc(it) }.sortedByDescending { it.updated }

        fun get(ctx: Context, id: String): LibDoc? = File(root(ctx), id).takeIf { File(it, "meta.json").exists() }?.let { LibDoc(it) }

        fun create(ctx: Context, src: File, name: String, format: DocFormat): LibDoc {
            val dir = File(root(ctx), newId()).apply { mkdirs() }
            val d = LibDoc(dir)
            d.name = name.substringBeforeLast('.').ifBlank { "document" }
            d.format = format
            src.copyTo(d.source, overwrite = true)
            d.save()
            return d
        }

        /** Ritar fälten (med vridning) ovanpå en sida. Används för miniatyrer och förhandsvisning. */
        fun drawFields(ctx: Context, c: Canvas, fields: List<Field>, w: Float, h: Float) {
            val p = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
            for (f in fields) {
                val r = RectF(f.x * w, f.y * h, (f.x + f.w) * w, (f.y + f.h) * h)
                if (!f.done) {
                    c.drawRoundRect(r, 4f, 4f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE; strokeWidth = 2f; color = 0xFFE8776D.toInt()
                        pathEffect = android.graphics.DashPathEffect(floatArrayOf(6f, 4f), 0f)
                    })
                    continue
                }
                val b = Composer.compose(ctx, f, r.width().toInt().coerceAtLeast(1), r.height().toInt().coerceAtLeast(1)) ?: continue
                c.save()
                c.rotate(f.rot, r.centerX(), r.centerY())
                c.drawBitmap(b, null, r, p)
                c.restore()
            }
        }
    }
}
