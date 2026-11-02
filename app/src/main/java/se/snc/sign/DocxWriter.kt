package se.snc.sign

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Sparar ett signerat Word-dokument (.docx). Signatur, foto och sigill läggs in som bilder som
 * ligger fritt på sidan (relativt sidans hörn), förankrade i ett stycke på rätt sida.
 * Originalets text och formatering lämnas orörda.
 */
object DocxWriter {

    const val EMU_PER_PT = 12700L

    class Placement(val anchor: Int, val x: Long, val y: Long, val cx: Long, val cy: Long, val png: ByteArray)

    fun write(src: File, out: File, placements: List<Placement>) {
        ZipFile(src).use { zip ->
            val entries = zip.entries().toList()
            val doc = zip.getEntry("word/document.xml") ?: error("word/document.xml saknas")
            val relsName = "word/_rels/document.xml.rels"
            var xml = zip.getInputStream(doc).use { String(it.readBytes(), Charsets.UTF_8) }
            var rels = zip.getEntry(relsName)?.let { e -> zip.getInputStream(e).use { String(it.readBytes(), Charsets.UTF_8) } }
                ?: """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"></Relationships>"""
            var types = zip.getInputStream(zip.getEntry("[Content_Types].xml")).use { String(it.readBytes(), Charsets.UTF_8) }

            val stamp = System.currentTimeMillis() % 100000
            val media = ArrayList<Pair<String, ByteArray>>()
            val runs = ArrayList<Pair<Int, String>>()
            placements.forEachIndexed { i, p ->
                val rId = "rIdSnc$stamp$i"
                val name = "media/snc_sign_${stamp}_$i.png"
                media += "word/$name" to p.png
                rels = rels.replace("</Relationships>",
                    """<Relationship Id="$rId" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="$name"/></Relationships>""")
                runs += p.anchor to drawingRun(rId, 9000 + i, p)
            }
            if (!Regex("""Extension="png"""", RegexOption.IGNORE_CASE).containsMatchIn(types)) {
                types = types.replaceFirst(Regex("(<Types[^>]*>)"), "$1<Default Extension=\"png\" ContentType=\"image/png\"/>")
            }
            xml = insertRuns(xml, runs)

            ZipOutputStream(out.outputStream().buffered()).use { zos ->
                for (e in entries) {
                    if (e.isDirectory) continue
                    val bytes = when (e.name) {
                        "word/document.xml" -> xml.toByteArray(Charsets.UTF_8)
                        relsName -> rels.toByteArray(Charsets.UTF_8)
                        "[Content_Types].xml" -> types.toByteArray(Charsets.UTF_8)
                        else -> zip.getInputStream(e).use { it.readBytes() }
                    }
                    zos.putNextEntry(ZipEntry(e.name)); zos.write(bytes); zos.closeEntry()
                }
                if (zip.getEntry(relsName) == null) {
                    zos.putNextEntry(ZipEntry(relsName)); zos.write(rels.toByteArray(Charsets.UTF_8)); zos.closeEntry()
                }
                for ((name, bytes) in media) {
                    zos.putNextEntry(ZipEntry(name)); zos.write(bytes); zos.closeEntry()
                }
            }
        }
    }

    /** Barnen till w:body (stycken, tabeller m.m.) som (namn, start, slut). */
    fun bodyChildren(xml: String): List<Triple<String, Int, Int>> {
        val bodyStart = xml.indexOf("<w:body")
        if (bodyStart < 0) return emptyList()
        val tag = Regex("""<(/?)([A-Za-z_][\w.\-]*:?[\w.\-]*)((?:[^>"']|"[^"]*"|'[^']*')*?)(/?)>""")
        val out = ArrayList<Triple<String, Int, Int>>()
        var depth = -1 // -1 = innan <w:body>, 0 = direkt i body
        var curName = ""; var curStart = 0
        for (m in tag.findAll(xml, bodyStart)) {
            val closing = m.groupValues[1] == "/"
            val name = m.groupValues[2]
            val selfClose = m.groupValues[4] == "/"
            if (depth == -1) { if (name == "w:body" && !closing) depth = 0; continue }
            if (closing) {
                if (depth == 0) break // </w:body>
                depth--
                if (depth == 0) out += Triple(curName, curStart, m.range.last + 1)
            } else if (depth == 0) {
                if (selfClose) out += Triple(name, m.range.first, m.range.last + 1)
                else { curName = name; curStart = m.range.first; depth = 1 }
            } else if (!selfClose) depth++
        }
        // Samma räkning som Word-förhandsvisningen: stycken och tabeller
        return out.filter { it.first == "w:p" || it.first == "w:tbl" || it.first == "w:sdt" }
    }

    /** Lägger varje bild-run i stycket med index [anchor] (eller närmaste stycke). */
    fun insertRuns(xml: String, runs: List<Pair<Int, String>>): String {
        val kids = bodyChildren(xml)
        val paragraphs = kids.withIndex().filter { it.value.first == "w:p" }
        if (paragraphs.isEmpty()) error("Dokumentet saknar stycken")
        // Gruppera per stycke och sätt in bakifrån så att positionerna inte förskjuts
        val byPara = runs.groupBy { (anchor, _) ->
            paragraphs.minByOrNull { kotlin.math.abs(it.index - anchor.coerceIn(0, kids.size - 1)) }!!.value
        }
        var s = xml
        for ((p, list) in byPara.entries.sortedByDescending { it.key.second }) {
            val content = list.joinToString("") { it.second }
            val elem = s.substring(p.second, p.third)
            s = if (elem.endsWith("/>")) {
                s.substring(0, p.second) + elem.dropLast(2).trimEnd() + ">" + content + "</w:p>" + s.substring(p.third)
            } else {
                val at = p.third - "</w:p>".length
                s.substring(0, at) + content + s.substring(at)
            }
        }
        return s
    }

    private fun drawingRun(rId: String, id: Int, p: Placement): String =
        """<w:r><w:drawing><wp:anchor xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing" distT="0" distB="0" distL="0" distR="0" simplePos="0" relativeHeight="${251660000 + id}" behindDoc="0" locked="0" layoutInCell="1" allowOverlap="1">""" +
            """<wp:simplePos x="0" y="0"/><wp:positionH relativeFrom="page"><wp:posOffset>${p.x}</wp:posOffset></wp:positionH>""" +
            """<wp:positionV relativeFrom="page"><wp:posOffset>${p.y}</wp:posOffset></wp:positionV>""" +
            """<wp:extent cx="${p.cx}" cy="${p.cy}"/><wp:effectExtent l="0" t="0" r="0" b="0"/><wp:wrapNone/>""" +
            """<wp:docPr id="$id" name="Sign $id" descr="Signed with Sign – S.N.C AB"/><wp:cNvGraphicFramePr/>""" +
            """<a:graphic xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/picture">""" +
            """<pic:pic xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture"><pic:nvPicPr><pic:cNvPr id="$id" name="sign$id.png"/><pic:cNvPicPr/></pic:nvPicPr>""" +
            """<pic:blipFill><a:blip xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" r:embed="$rId"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>""" +
            """<pic:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="${p.cx}" cy="${p.cy}"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></pic:spPr></pic:pic>""" +
            """</a:graphicData></a:graphic></wp:anchor></w:drawing></w:r>"""
}
