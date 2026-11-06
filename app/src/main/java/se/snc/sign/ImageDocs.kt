package se.snc.sign

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Sparar ett dokument där varje sida är en bild med signaturerna inritade:
 * Word (.docx), ODT eller en bild per sida (PNG/JPG). Används när originalet inte kan skrivas
 * i det formatet (t.ex. PDF → Word). Texten blir då inte redigerbar.
 */
object ImageDocs {

    private const val PAGE_PX = 1700

    /** Sidan som bild med alla fält (signatur, foto, stämpel) inritade. */
    fun pageImage(ctx: Context, pages: Pages, i: Int, fields: List<Field>): Bitmap {
        val base = pages.render(i, PAGE_PX)
        val out = Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.WHITE)
        c.drawBitmap(base, null, RectF(0f, 0f, out.width.toFloat(), out.height.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
        LibDoc.drawFields(ctx, c, fields.filter { it.page == i && it.done }, out.width.toFloat(), out.height.toFloat())
        return out
    }

    /** En bildfil per sida i [dir]. */
    fun images(ctx: Context, pages: Pages, fields: List<Field>, dir: File, png: Boolean): List<File> {
        dir.deleteRecursively(); dir.mkdirs()
        return (0 until pages.count).map { i ->
            val f = File(dir, "page-%02d.%s".format(i + 1, if (png) "png" else "jpg"))
            val b = pageImage(ctx, pages, i, fields)
            f.outputStream().use { b.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, if (png) 100 else 92, it) }
            b.recycle()
            f
        }
    }

    private fun jpeg(b: Bitmap) = ByteArrayOutputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 90, it); it.toByteArray() }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** Word-dokument med en sida per bild. Varje sida får sin egen storlek och inga marginaler. */
    fun docx(ctx: Context, pages: Pages, fields: List<Field>, out: File, title: String) {
        val emu = DocxWriter.EMU_PER_PT
        val body = StringBuilder()
        val media = ArrayList<Pair<String, ByteArray>>()
        val rels = StringBuilder()
        for (i in 0 until pages.count) {
            val b = pageImage(ctx, pages, i, fields)
            media += "word/media/page$i.jpg" to jpeg(b); b.recycle()
            rels.append("""<Relationship Id="rIdP$i" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="media/page$i.jpg"/>""")
            val wPt = pages.widthPt(i); val hPt = pages.heightPt(i)
            // Lite mindre än sidan så att Word inte flyttar bilden till nästa sida
            val cx = (wPt * 0.995f * emu).toLong(); val cy = (hPt * 0.99f * emu).toLong()
            val wTw = (wPt * 20).toInt(); val hTw = (hPt * 20).toInt()
            val sect = """<w:sectPr><w:pgSz w:w="$wTw" w:h="$hTw"${if (wPt > hPt) " w:orient=\"landscape\"" else ""}/><w:pgMar w:top="0" w:right="0" w:bottom="0" w:left="0" w:header="0" w:footer="0" w:gutter="0"/></w:sectPr>"""
            val pPr = """<w:pPr><w:spacing w:before="0" w:after="0" w:line="240" w:lineRule="auto"/><w:jc w:val="center"/>${if (i < pages.count - 1) sect else ""}</w:pPr>"""
            body.append("""<w:p>$pPr<w:r><w:drawing><wp:inline distT="0" distB="0" distL="0" distR="0"><wp:extent cx="$cx" cy="$cy"/><wp:docPr id="${i + 1}" name="${esc(title)} ${i + 1}"/><a:graphic xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/picture"><pic:pic xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture"><pic:nvPicPr><pic:cNvPr id="${i + 1}" name="page$i.jpg"/><pic:cNvPicPr/></pic:nvPicPr><pic:blipFill><a:blip r:embed="rIdP$i"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill><pic:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="$cx" cy="$cy"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></pic:spPr></pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>""")
            if (i == pages.count - 1) body.append(sect)
        }
        val document = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"><w:body>$body</w:body></w:document>"""
        writeDocx(out, document, rels.toString(), media)
    }

    /** Skriver ett minimalt Word-paket. Används även av DocToDocx. */
    fun writeDocx(out: File, document: String, imageRels: String, media: List<Pair<String, ByteArray>>, styles: String? = null) {
        val types = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Default Extension="jpg" ContentType="image/jpeg"/><Default Extension="jpeg" ContentType="image/jpeg"/><Default Extension="png" ContentType="image/png"/><Default Extension="gif" ContentType="image/gif"/><Default Extension="bmp" ContentType="image/bmp"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>${if (styles != null) "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>" else ""}<Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/></Types>"""
        val rootRels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/></Relationships>"""
        val docRels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">${if (styles != null) "<Relationship Id=\"rIdStyles\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>" else ""}$imageRels</Relationships>"""
        val core = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:creator>Sign – Scandinavian Nexum Consulting AB</dc:creator></cp:coreProperties>"""
        ZipOutputStream(out.outputStream().buffered()).use { z ->
            fun put(name: String, bytes: ByteArray) { z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry() }
            put("[Content_Types].xml", types.toByteArray())
            put("_rels/.rels", rootRels.toByteArray())
            put("docProps/core.xml", core.toByteArray())
            put("word/document.xml", document.toByteArray())
            put("word/_rels/document.xml.rels", docRels.toByteArray())
            if (styles != null) put("word/styles.xml", styles.toByteArray())
            media.forEach { (n, b) -> put(n, b) }
        }
    }

    /** ODT med en sida per bild. */
    fun odt(ctx: Context, pages: Pages, fields: List<Field>, out: File) {
        val w0 = pages.widthPt(0); val h0 = pages.heightPt(0)
        fun cm(pt: Float) = "%.3fcm".format(java.util.Locale.ROOT, pt / 72f * 2.54f)
        val body = StringBuilder()
        val media = ArrayList<Pair<String, ByteArray>>()
        for (i in 0 until pages.count) {
            val b = pageImage(ctx, pages, i, fields)
            media += "Pictures/page$i.jpg" to jpeg(b); b.recycle()
            val wPt = pages.widthPt(i) * 0.995f; val hPt = pages.heightPt(i) * 0.99f
            body.append("""<text:p text:style-name="${if (i == 0) "P0" else "P1"}"><draw:frame draw:name="page$i" text:anchor-type="as-char" svg:width="${cm(wPt)}" svg:height="${cm(hPt)}"><draw:image xlink:href="Pictures/page$i.jpg" xlink:type="simple" xlink:show="embed" xlink:actuate="onLoad"/></draw:frame></text:p>""")
        }
        val ns = """xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0" xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0" xmlns:draw="urn:oasis:names:tc:opendocument:xmlns:drawing:1.0" xmlns:fo="urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0" xmlns:xlink="http://www.w3.org/1999/xlink" xmlns:svg="urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0" office:version="1.2""""
        val content = """<?xml version="1.0" encoding="UTF-8"?><office:document-content $ns><office:automatic-styles><style:style style:name="P0" style:family="paragraph"><style:paragraph-properties fo:margin-top="0cm" fo:margin-bottom="0cm" fo:text-align="center"/></style:style><style:style style:name="P1" style:family="paragraph"><style:paragraph-properties fo:break-before="page" fo:margin-top="0cm" fo:margin-bottom="0cm" fo:text-align="center"/></style:style></office:automatic-styles><office:body><office:text>$body</office:text></office:body></office:document-content>"""
        val styles = """<?xml version="1.0" encoding="UTF-8"?><office:document-styles $ns><office:automatic-styles><style:page-layout style:name="pm1"><style:page-layout-properties fo:page-width="${cm(w0)}" fo:page-height="${cm(h0)}" fo:margin-top="0cm" fo:margin-bottom="0cm" fo:margin-left="0cm" fo:margin-right="0cm" style:print-orientation="${if (w0 > h0) "landscape" else "portrait"}"/></style:page-layout></office:automatic-styles><office:master-styles><style:master-page style:name="Standard" style:page-layout-name="pm1"/></office:master-styles></office:document-styles>"""
        val manifest = """<?xml version="1.0" encoding="UTF-8"?><manifest:manifest xmlns:manifest="urn:oasis:names:tc:opendocument:xmlns:manifest:1.0" manifest:version="1.2"><manifest:file-entry manifest:full-path="/" manifest:media-type="application/vnd.oasis.opendocument.text"/><manifest:file-entry manifest:full-path="content.xml" manifest:media-type="text/xml"/><manifest:file-entry manifest:full-path="styles.xml" manifest:media-type="text/xml"/>${media.joinToString("") { "<manifest:file-entry manifest:full-path=\"${it.first}\" manifest:media-type=\"image/jpeg\"/>" }}</manifest:manifest>"""
        ZipOutputStream(out.outputStream().buffered()).use { z ->
            // mimetype först och okomprimerad
            val mt = "application/vnd.oasis.opendocument.text".toByteArray()
            val e = ZipEntry("mimetype").apply { method = ZipEntry.STORED; size = mt.size.toLong(); compressedSize = mt.size.toLong(); crc = CRC32().apply { update(mt) }.value }
            z.putNextEntry(e); z.write(mt); z.closeEntry()
            fun put(name: String, bytes: ByteArray) { z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry() }
            put("content.xml", content.toByteArray())
            put("styles.xml", styles.toByteArray())
            put("META-INF/manifest.xml", manifest.toByteArray())
            media.forEach { (n, b) -> put(n, b) }
        }
    }
}
