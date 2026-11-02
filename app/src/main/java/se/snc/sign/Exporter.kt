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
            val wPt = f.w * pages.widthPt(f.page); val hPt = f.h * pages.heightPt(f.page)
            val comp = Composer.compose(ctx, f, (wPt * PX_PER_PT).toInt(), (hPt * PX_PER_PT).toInt()) ?: return@mapNotNull null
            val png = java.io.ByteArrayOutputStream().use { comp.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }
            DocxWriter.Placement(
                anchor = pages.anchors.getOrElse(f.page) { 0 },
                x = (f.x * pages.widthPt(f.page) * e).toLong(),
                y = (f.y * pages.heightPt(f.page) * e).toLong(),
                cx = (wPt * e).toLong(), cy = (hPt * e).toLong(), png = png,
            )
        }
        DocxWriter.write(source, out, placements)
    }

    fun export(ctx: Context, source: File, isPdf: Boolean, pages: Pages, fields: List<Field>, out: File) {
        PDFBoxResourceLoader.init(ctx.applicationContext)
        val doc = if (isPdf) PDDocument.load(source) else PDDocument()
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
                val pxW = (f.w * dispW * PX_PER_PT).toInt()
                val pxH = (f.h * dispH * PX_PER_PT).toInt()
                val comp = Composer.compose(ctx, f, pxW, pxH) ?: continue

                // Visningskoordinater (origo uppe till vänster) räknas om till PDF-koordinater.
                val W = box.width; val H = box.height
                val llx = box.lowerLeftX; val lly = box.lowerLeftY
                val urx = box.upperRightX; val ury = box.upperRightY
                val (x0, x1, y0, y1) = when (rot) {
                    90 -> listOf(llx + f.y * W, llx + (f.y + f.h) * W, lly + f.x * H, lly + (f.x + f.w) * H)
                    180 -> listOf(urx - (f.x + f.w) * W, urx - f.x * W, lly + f.y * H, lly + (f.y + f.h) * H)
                    270 -> listOf(urx - (f.y + f.h) * W, urx - f.y * W, ury - (f.x + f.w) * H, ury - f.x * H)
                    else -> listOf(llx + f.x * W, llx + (f.x + f.w) * W, ury - (f.y + f.h) * H, ury - f.y * H)
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
                producer = "Sign – Claes Sundman, S.N.C AB"
                creator = "Sign"
                modificationDate = Calendar.getInstance()
            }
            // Krypterade PDF:er (t.ex. med utskriftsskydd) sparas utan skyddet så att signaturen kan läggas till.
            if (d.isEncrypted) d.isAllSecurityToBeRemoved = true
            d.save(out)
        }
    }
}
