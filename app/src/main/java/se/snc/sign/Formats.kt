package se.snc.sign

import android.util.Base64
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipFile

/** Dokumentformaten som Sign kan läsa in. Formatet känns igen på innehållet, inte bara på filändelsen. */
enum class DocFormat(val label: String, val ext: String, val mime: String) {
    PDF("PDF", "pdf", "application/pdf"),
    DOCX("DOCX", "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
    ODT("ODT", "odt", "application/vnd.oasis.opendocument.text"),
    DOC("DOC", "doc", "application/msword"),
    SCAN("PDF", "pdf", "application/pdf");

    companion object {
        /** Filtyper som visas i filväljaren. octet-stream fångar filer som saknar rätt typ. */
        val PICKER_MIMES = arrayOf(
            "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.oasis.opendocument.text",
            "application/msword",
            "application/octet-stream",
        )

        fun detect(file: File, name: String): DocFormat? {
            val head = ByteArray(8)
            val n = FileInputStream(file).use { it.read(head) }
            if (n >= 5 && String(head, 0, 5, Charsets.ISO_8859_1) == "%PDF-") return PDF
            // OLE2 (gamla Word .doc)
            if (n >= 8 && head[0] == 0xD0.toByte() && head[1] == 0xCF.toByte() && head[2] == 0x11.toByte() && head[3] == 0xE0.toByte()) return DOC
            if (n >= 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()) {
                runCatching {
                    ZipFile(file).use { z ->
                        val mt = z.getEntry("mimetype")?.let { e -> z.getInputStream(e).use { String(it.readBytes()).trim() } }
                        if (mt == "application/vnd.oasis.opendocument.text") return ODT
                        if (z.getEntry("word/document.xml") != null) return DOCX
                    }
                }
            }
            // Sista utvägen: filändelsen
            return when (name.substringAfterLast('.', "").lowercase()) {
                "pdf" -> PDF; "docx" -> DOCX; "odt" -> ODT; "doc" -> DOC
                else -> null
            }
        }
    }
}

/**
 * Gamla Word-dokument (.doc) görs om till HTML med Apache POI och ritas sedan upp som sidor.
 * Går något fel (ovanliga .doc-filer) används ren text, så att dokumentet ändå kan signeras.
 */
object DocToHtml {

    class Result(val html: String, val layoutJson: String)

    fun convert(file: File): Result {
        var layout = """{"w":793.7,"h":1122.5,"mt":75.6,"mb":75.6,"ml":90.7,"mr":90.7}"""
        val html = try {
            val doc = org.apache.poi.hwpf.HWPFDocument(FileInputStream(file))
            runCatching {
                val sec = doc.range.getSection(0)
                // twips → CSS-pixlar (1440 twips = 1 tum = 96 px)
                fun px(t: Int) = t / 15f
                if (sec.pageWidth > 0 && sec.pageHeight > 0) {
                    layout = """{"w":${px(sec.pageWidth)},"h":${px(sec.pageHeight)},"mt":${px(sec.marginTop)},"mb":${px(sec.marginBottom)},"ml":${px(sec.marginLeft)},"mr":${px(sec.marginRight)}}"""
                }
            }
            try {
                val dom = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument()
                val conv = org.apache.poi.hwpf.converter.WordToHtmlConverter(dom)
                conv.setPicturesManager { content, type, _, _, _ ->
                    "data:" + (type?.mime ?: "image/png") + ";base64," + Base64.encodeToString(content, Base64.NO_WRAP)
                }
                conv.processDocument(doc)
                val out = java.io.StringWriter()
                val t = javax.xml.transform.TransformerFactory.newInstance().newTransformer()
                t.setOutputProperty(javax.xml.transform.OutputKeys.METHOD, "html")
                t.setOutputProperty(javax.xml.transform.OutputKeys.ENCODING, "UTF-8")
                t.transform(javax.xml.transform.dom.DOMSource(conv.document), javax.xml.transform.stream.StreamResult(out))
                val full = out.toString()
                val style = Regex("<style[^>]*>([\\s\\S]*?)</style>", RegexOption.IGNORE_CASE).find(full)?.groupValues?.get(1) ?: ""
                val body = Regex("<body[^>]*>([\\s\\S]*)</body>", RegexOption.IGNORE_CASE).find(full)?.groupValues?.get(1) ?: full
                "<style>$style</style>$body"
            } catch (t: Throwable) {
                textOnly(org.apache.poi.hwpf.extractor.WordExtractor(doc).paragraphText.toList())
            }
        } catch (t: Throwable) {
            throw IllegalStateException("doc", t)
        }
        return Result(html, layout)
    }

    private fun textOnly(paragraphs: List<String>) =
        paragraphs.joinToString("") { p ->
            val t = p.trimEnd('\r', '\n').replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            "<p>${t.ifBlank { "&#8203;" }}</p>"
        }
}
