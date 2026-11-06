package se.snc.sign

import org.apache.poi.hwpf.HWPFDocument
import org.apache.poi.hwpf.usermodel.CharacterRun
import org.apache.poi.hwpf.usermodel.Paragraph
import org.apache.poi.hwpf.usermodel.Range
import java.io.File
import java.io.FileInputStream

/**
 * Gör om ett gammalt Word-dokument (.doc) till en riktig Word-fil (.docx) med redigerbar text:
 * stycken, rubriker, fet/kursiv/understruken text, storlek och färg, justering, tabeller, bilder,
 * radbrytningar och sidbrytningar. Enklare layout kan ändras något. Signaturerna läggs sedan in
 * på rätt sida med DocxWriter.
 */
object DocToDocx {

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    fun convert(src: File, out: File) {
        val doc = HWPFDocument(FileInputStream(src))
        val range = doc.range
        val media = ArrayList<Pair<String, ByteArray>>()
        val rels = StringBuilder()
        var picN = 0
        var inFieldCode = false
        val styles = doc.styleSheet

        fun runXml(r: CharacterRun): String {
            val sb = StringBuilder()
            val rPr = StringBuilder()
            if (r.isBold) rPr.append("<w:b/>")
            if (r.isItalic) rPr.append("<w:i/>")
            if (r.underlineCode != 0) rPr.append("<w:u w:val=\"single\"/>")
            if (r.isStrikeThrough) rPr.append("<w:strike/>")
            runCatching { r.fontName }.getOrNull()?.takeIf { it.isNotBlank() }?.let { rPr.append("<w:rFonts w:ascii=\"${esc(it)}\" w:hAnsi=\"${esc(it)}\" w:cs=\"${esc(it)}\"/>") }
            runCatching { r.ico24 }.getOrNull()?.takeIf { it != -1 }?.let { c ->
                // ico24 är BGR
                val rgb = ((c and 0xFF) shl 16) or (c and 0xFF00) or ((c shr 16) and 0xFF)
                rPr.append("<w:color w:val=\"%06X\"/>".format(rgb))
            }
            if (r.fontSize > 0) rPr.append("<w:sz w:val=\"${r.fontSize}\"/>")
            val props = if (rPr.isNotEmpty()) "<w:rPr>$rPr</w:rPr>" else ""

            // Bild i texten
            if (doc.picturesTable.hasPicture(r)) {
                val pic = runCatching { doc.picturesTable.extractPicture(r, false) }.getOrNull()
                if (pic != null) {
                    val ext = pic.suggestFileExtension().ifBlank { "png" }
                    val name = "media/img$picN.$ext"
                    media += "word/$name" to pic.content
                    val rid = "rIdImg$picN"
                    rels.append("""<Relationship Id="$rid" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="$name"/>""")
                    val wTw = (pic.dxaGoal * pic.horizontalScalingFactor / 1000).coerceAtLeast(200)
                    val hTw = (pic.dyaGoal * pic.verticalScalingFactor / 1000).coerceAtLeast(200)
                    val cx = wTw * 635L; val cy = hTw * 635L
                    picN++
                    return """<w:r>$props<w:drawing><wp:inline distT="0" distB="0" distL="0" distR="0"><wp:extent cx="$cx" cy="$cy"/><wp:docPr id="${1000 + picN}" name="img$picN"/><a:graphic xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/picture"><pic:pic xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture"><pic:nvPicPr><pic:cNvPr id="${1000 + picN}" name="img$picN.$ext"/><pic:cNvPicPr/></pic:nvPicPr><pic:blipFill><a:blip r:embed="$rid"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill><pic:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="$cx" cy="$cy"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></pic:spPr></pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r>"""
                }
            }

            val text = r.text()
            val buf = StringBuilder()
            fun flush() { if (buf.isNotEmpty()) { sb.append("<w:r>$props<w:t xml:space=\"preserve\">${esc(buf.toString())}</w:t></w:r>"); buf.setLength(0) } }
            for (ch in text) {
                when (ch) {
                    '\u0013' -> { flush(); inFieldCode = true }      // fält börjar (koden döljs)
                    '\u0014' -> { flush(); inFieldCode = false }     // fältets resultat visas
                    '\u0015' -> { flush(); inFieldCode = false }
                    '\r', '\u0007', '\u0001', '\u0008' -> {}
                    '\u000b' -> { flush(); sb.append("<w:r>$props<w:br/></w:r>") }
                    '\u000c' -> { flush(); sb.append("<w:r><w:br w:type=\"page\"/></w:r>") }
                    '\t' -> { flush(); sb.append("<w:r>$props<w:tab/></w:r>") }
                    else -> if (!inFieldCode && ch >= ' ') buf.append(ch)
                }
            }
            flush()
            return sb.toString()
        }

        fun paraXml(p: Paragraph): String {
            val pPr = StringBuilder()
            val styleName = runCatching { styles.getStyleDescription(p.styleIndex.toInt())?.name }.getOrNull()?.lowercase() ?: ""
            Regex("""heading\s*(\d)""").find(styleName)?.let { pPr.append("<w:pStyle w:val=\"Heading${it.groupValues[1]}\"/>") }
            if (styleName == "title") pPr.append("<w:pStyle w:val=\"Title\"/>")
            when (p.justification.toInt()) {
                1 -> pPr.append("<w:jc w:val=\"center\"/>")
                2 -> pPr.append("<w:jc w:val=\"right\"/>")
                3 -> pPr.append("<w:jc w:val=\"both\"/>")
            }
            if (p.indentFromLeft > 0) pPr.append("<w:ind w:left=\"${p.indentFromLeft}\"/>")
            val sp = "<w:spacing w:before=\"${p.spacingBefore.coerceAtLeast(0)}\" w:after=\"${p.spacingAfter.coerceAtLeast(0)}\"/>"
            val runs = StringBuilder()
            for (k in 0 until p.numCharacterRuns()) runs.append(runXml(p.getCharacterRun(k)))
            return "<w:p><w:pPr>${pPr}$sp</w:pPr>$runs</w:p>"
        }

        fun rangeXml(r: Range): String {
            val sb = StringBuilder()
            var i = 0
            while (i < r.numParagraphs()) {
                val p = r.getParagraph(i)
                if (p.isInTable && r === range) {
                    val t = runCatching { r.getTable(p) }.getOrNull()
                    if (t != null) {
                        sb.append("<w:tbl><w:tblPr><w:tblW w:w=\"0\" w:type=\"auto\"/><w:tblBorders>")
                        for (b in listOf("top", "left", "bottom", "right", "insideH", "insideV")) sb.append("<w:$b w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"999999\"/>")
                        sb.append("</w:tblBorders></w:tblPr>")
                        for (ri in 0 until t.numRows()) {
                            val row = t.getRow(ri)
                            sb.append("<w:tr>")
                            for (ci in 0 until row.numCells()) {
                                val cell = row.getCell(ci)
                                sb.append("<w:tc><w:tcPr><w:tcW w:w=\"${cell.width.coerceAtLeast(0)}\" w:type=\"dxa\"/></w:tcPr>")
                                var any = false
                                for (k in 0 until cell.numParagraphs()) { sb.append(paraXml(cell.getParagraph(k))); any = true }
                                if (!any) sb.append("<w:p/>")
                                sb.append("</w:tc>")
                            }
                            sb.append("</w:tr>")
                        }
                        sb.append("</w:tbl>")
                        i += t.numParagraphs().coerceAtLeast(1)
                        continue
                    }
                }
                sb.append(paraXml(p))
                i++
            }
            return sb.toString()
        }

        val bodyXml = rangeXml(range)
        // Sidstorlek och marginaler från första avsnittet
        val sect = runCatching {
            val s = range.getSection(0)
            """<w:sectPr><w:pgSz w:w="${s.pageWidth}" w:h="${s.pageHeight}"/><w:pgMar w:top="${s.marginTop}" w:right="${s.marginRight}" w:bottom="${s.marginBottom}" w:left="${s.marginLeft}" w:header="708" w:footer="708" w:gutter="0"/></w:sectPr>"""
        }.getOrDefault("""<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1417" w:right="1417" w:bottom="1417" w:left="1417" w:header="708" w:footer="708" w:gutter="0"/></w:sectPr>""")
        val document = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"><w:body>$bodyXml$sect</w:body></w:document>"""
        val stylesXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="Times New Roman" w:hAnsi="Times New Roman"/><w:sz w:val="24"/></w:rPr></w:rPrDefault></w:docDefaults>""" +
            (1..3).joinToString("") { n -> """<w:style w:type="paragraph" w:styleId="Heading$n"><w:name w:val="heading $n"/><w:basedOn w:val="Normal"/><w:pPr><w:keepNext/><w:outlineLvl w:val="${n - 1}"/></w:pPr><w:rPr><w:b/><w:sz w:val="${36 - n * 4}"/></w:rPr></w:style>""" } +
            """<w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/></w:style><w:style w:type="paragraph" w:styleId="Title"><w:name w:val="Title"/><w:rPr><w:b/><w:sz w:val="40"/></w:rPr></w:style></w:styles>"""
        ImageDocs.writeDocx(out, document, rels.toString(), media, stylesXml)
    }
}
