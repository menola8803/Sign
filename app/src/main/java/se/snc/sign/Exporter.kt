package se.snc.sign

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import java.io.File
import java.util.Calendar

/**
 * Skapar den signerade PDF:en. För PDF skrivs signaturer och stämplar in i originalfilen,
 * så text och vektorer behålls. Word-dokument blir en ny PDF med en bild per sida.
 */
object Exporter {

    private const val PX_PER_PT = 4f // ≈ 288 dpi för signatur och foto

    fun signedName(original: String, ext: String = "pdf"): String {
        val base = original.substringBeforeLast('.')
        return "$base-signed.$ext"
    }

    /** Signerat Word-dokument: originalet med signatur, foto och sigill inlagda som bilder på rätt plats. */
    fun exportDocx(ctx: Context, source: File, pages: ImagePages, fields: List<Field>, out: File) {
        val e = DocxWriter.EMU_PER_PT.toFloat()
        val placements = fields.filter { it.done && it.page < pages.count }.mapNotNull { f ->
            val pw = pages.widthPt(f.page); val ph = pages.heightPt(f.page)
            val (img, r) = Composer.render(ctx, f, pw, ph, PX_PER_PT) ?: return@mapNotNull null
            val png = java.io.ByteArrayOutputStream().use { img.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }
            DocxWriter.Placement(
                anchor = pages.anchors.getOrElse(f.page) { 0 },
                x = (r.left * pw * e).toLong(), y = (r.top * ph * e).toLong(),
                cx = (r.width() * pw * e).toLong(), cy = (r.height() * ph * e).toLong(), png = png,
            )
        }
        DocxWriter.write(source, out, placements)
    }

    /** Signerad ODT: originalet med bilderna inlagda, förankrade på rätt sida. */
    fun exportOdt(ctx: Context, source: File, pages: ImagePages, fields: List<Field>, out: File) {
        val placements = fields.filter { it.done && it.page < pages.count }.mapNotNull { f ->
            val pw = pages.widthPt(f.page); val ph = pages.heightPt(f.page)
            val (img, r) = Composer.render(ctx, f, pw, ph, PX_PER_PT) ?: return@mapNotNull null
            val png = java.io.ByteArrayOutputStream().use { img.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }
            OdtWriter.Placement(f.page + 1, r.left * pw, r.top * ph, r.width() * pw, r.height() * ph, png)
        }
        OdtWriter.write(source, out, placements)
    }

    /**
     * Signerad PDF. För en PDF skrivs bilderna in i originalet (text och vektorer behålls).
     * För Word, ODT, DOC och fotograferade dokument blir det en ny PDF med en bild per sida.
     * password används för lösenordsskyddade PDF:er.
     */
    fun export(ctx: Context, source: File, isPdf: Boolean, pages: Pages, fields: List<Field>, out: File, password: String? = null) {
        PDFBoxResourceLoader.init(ctx.applicationContext)
        val doc = if (isPdf) (if (password != null) PDDocument.load(source, password) else PDDocument.load(source)) else PDDocument()
        doc.use { d ->
            if (!isPdf) {
                val img = pages as ImagePages
                for (i in 0 until img.count) {
                    val rect = PDRectangle(img.widthPt(i), img.heightPt(i))
                    val page = PDPage(rect)
                    d.addPage(page)
                    val bmp = BitmapFactory.decodeFile(img.file(i).path)
                    val x = JPEGFactory.createFromImage(d, bmp, 0.92f)
                    PDPageContentStream(d, page).use { it.drawImage(x, 0f, 0f, rect.width, rect.height) }
                    bmp.recycle()
                }
            }
            for (f in fields) {
                if (!f.done || f.page >= d.numberOfPages) continue
                val page = d.getPage(f.page)
                val rot = ((page.rotation % 360) + 360) % 360
                val box = page.cropBox
                // Sidan som den visas (efter rotation)
                val dispW = if (rot == 90 || rot == 270) box.height else box.width
                val dispH = if (rot == 90 || rot == 270) box.width else box.height
                val (comp, r) = Composer.render(ctx, f, dispW, dispH, PX_PER_PT) ?: continue
                val fx = r.left; val fy = r.top; val fw = r.width(); val fh = r.height()

                // Visningskoordinater (origo uppe till vänster) räknas om till PDF-koordinater.
                val W = box.width; val H = box.height
                val llx = box.lowerLeftX; val lly = box.lowerLeftY
                val urx = box.upperRightX; val ury = box.upperRightY
                val (x0, x1, y0, y1) = when (rot) {
                    90 -> listOf(llx + fy * W, llx + (fy + fh) * W, lly + fx * H, lly + (fx + fw) * H)
                    180 -> listOf(urx - (fx + fw) * W, urx - fx * W, lly + fy * H, lly + (fy + fh) * H)
                    270 -> listOf(urx - (fy + fh) * W, urx - fy * W, ury - (fx + fw) * H, ury - fx * H)
                    else -> listOf(llx + fx * W, llx + (fx + fw) * W, ury - (fy + fh) * H, ury - fy * H)
                }
                // Bilden vrids mot sidans rotation så att den syns rättvänd.
                val upright = if (rot == 0) comp else Bitmap.createBitmap(
                    comp, 0, 0, comp.width, comp.height, Matrix().apply { postRotate(-rot.toFloat()) }, true
                )
                val image = LosslessFactory.createFromImage(d, upright)
                PDPageContentStream(d, page, PDPageContentStream.AppendMode.APPEND, true, true).use {
                    it.drawImage(image, x0, y0, x1 - x0, y1 - y0)
                }
            }
            d.documentInformation.apply {
                producer = "Sign – Claes Sundman, Scandinavian Nexum Consulting AB"
                creator = "Sign"
                modificationDate = Calendar.getInstance()
            }
            // Krypterade PDF:er (t.ex. med utskriftsskydd) sparas utan skyddet så att signaturen kan läggas till.
            if (d.isEncrypted) d.isAllSecurityToBeRemoved = true
            d.save(out)
        }
    }
}
