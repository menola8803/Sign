package se.snc.sign

import java.io.File
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Sparar ett signerat OpenDocument-dokument (.odt). Signatur, foto och sigill läggs in som bilder
 * förankrade på rätt sida (text:anchor-type="page") med exakt läge från sidans övre vänstra hörn.
 * Originalets text och formatering lämnas orörda.
 */
object OdtWriter {

    /** page börjar på 1. Läge och storlek i punkter (pt). */
    class Placement(val page: Int, val xPt: Float, val yPt: Float, val wPt: Float, val hPt: Float, val png: ByteArray)

    private fun cm(pt: Float) = String.format(Locale.US, "%.3fcm", pt / 72f * 2.54f)

    fun write(src: File, out: File, placements: List<Placement>) {
        ZipFile(src).use { zip ->
            val contentEntry = zip.getEntry("content.xml") ?: error("content.xml saknas")
            var content = zip.getInputStream(contentEntry).use { String(it.readBytes(), Charsets.UTF_8) }
            var manifest = zip.getEntry("META-INF/manifest.xml")?.let { e -> zip.getInputStream(e).use { String(it.readBytes(), Charsets.UTF_8) } }
                ?: error("manifest saknas")

            val stamp = System.currentTimeMillis() % 100000
            val media = ArrayList<Pair<String, ByteArray>>()
            val frames = StringBuilder()
            placements.forEachIndexed { i, p ->
                val name = "Pictures/snc_sign_${stamp}_$i.png"
                media += name to p.png
                frames.append(
                    """<draw:frame draw:style-name="SncSignFrame" draw:name="Sign $stamp $i" text:anchor-type="page" text:anchor-page-number="${p.page}" """ +
                        """svg:x="${cm(p.xPt)}" svg:y="${cm(p.yPt)}" svg:width="${cm(p.wPt)}" svg:height="${cm(p.hPt)}" draw:z-index="${100 + i}">""" +
                        """<draw:image xlink:href="$name" xlink:type="simple" xlink:show="embed" xlink:actuate="onLoad"/></draw:frame>"""
                )
                manifest = manifest.replace(
                    "</manifest:manifest>",
                    """ <manifest:file-entry manifest:full-path="$name" manifest:media-type="image/png"/>""" + "\n</manifest:manifest>"
                )
            }
            content = ensureNamespaces(content)
            content = addFrameStyle(content)
            content = insertFrames(content, frames.toString())

            ZipOutputStream(out.outputStream().buffered()).use { zos ->
                // "mimetype" måste ligga först och vara okomprimerad
                zip.getEntry("mimetype")?.let { e ->
                    val bytes = zip.getInputStream(e).use { it.readBytes() }
                    val crc = CRC32().apply { update(bytes) }
                    zos.putNextEntry(ZipEntry("mimetype").apply {
                        method = ZipEntry.STORED; size = bytes.size.toLong(); compressedSize = bytes.size.toLong(); this.crc = crc.value
                    })
                    zos.write(bytes); zos.closeEntry()
                }
                for (e in zip.entries().toList()) {
                    if (e.isDirectory || e.name == "mimetype") continue
                    val bytes = when (e.name) {
                        "content.xml" -> content.toByteArray(Charsets.UTF_8)
                        "META-INF/manifest.xml" -> manifest.toByteArray(Charsets.UTF_8)
                        else -> zip.getInputStream(e).use { it.readBytes() }
                    }
                    zos.putNextEntry(ZipEntry(e.name)); zos.write(bytes); zos.closeEntry()
                }
                for ((name, bytes) in media) {
                    zos.putNextEntry(ZipEntry(name)); zos.write(bytes); zos.closeEntry()
                }
            }
        }
    }

    /** Stil som placerar ramen exakt från sidans övre vänstra hörn, framför texten. */
    private fun addFrameStyle(xml: String): String {
        val style = """<style:style style:name="SncSignFrame" style:family="graphic"><style:graphic-properties """ +
            """style:wrap="run-through" style:run-through="foreground" style:vertical-pos="from-top" style:vertical-rel="page" """ +
            """style:horizontal-pos="from-left" style:horizontal-rel="page" fo:border="none" fo:padding="0cm" """ +
            """style:shadow="none" draw:stroke="none" draw:fill="none"/></style:style>"""
        val selfClosed = Regex("<office:automatic-styles\\s*/>")
        return when {
            selfClosed.containsMatchIn(xml) -> xml.replaceFirst(selfClosed, "<office:automatic-styles>$style</office:automatic-styles>")
            xml.contains("<office:automatic-styles>") -> xml.replaceFirst("<office:automatic-styles>", "<office:automatic-styles>$style")
            else -> {
                val i = xml.indexOf("<office:body")
                xml.substring(0, i) + "<office:automatic-styles>$style</office:automatic-styles>" + xml.substring(i)
            }
        }
    }

    /** Lägger till namnrymderna för draw, svg, xlink och text om de saknas i rotelementet. */
    private fun ensureNamespaces(xml: String): String {
        val start = xml.indexOf("<office:document-content")
        if (start < 0) return xml
        val end = xml.indexOf('>', start)
        var root = xml.substring(start, end)
        val ns = mapOf(
            "draw" to "urn:oasis:names:tc:opendocument:xmlns:drawing:1.0",
            "svg" to "urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0",
            "xlink" to "http://www.w3.org/1999/xlink",
            "text" to "urn:oasis:names:tc:opendocument:xmlns:text:1.0",
            "style" to "urn:oasis:names:tc:opendocument:xmlns:style:1.0",
            "fo" to "urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0",
        )
        for ((p, uri) in ns) if (!root.contains("xmlns:$p=")) root += """ xmlns:$p="$uri""""
        return xml.substring(0, start) + root + xml.substring(end)
    }

    /**
     * Sidförankrade ramar läggs i office:text efter eventuella deklarationer
     * (text:sequence-decls m.fl.), före det första stycket, rubriken, tabellen eller listan.
     */
    private fun insertFrames(xml: String, frames: String): String {
        val body = xml.indexOf("<office:text")
        if (body < 0) error("office:text saknas")
        val bodyOpenEnd = xml.indexOf('>', body)
        if (xml[bodyOpenEnd - 1] == '/') {
            // Tomt dokument: <office:text/>
            return xml.substring(0, body) + "<office:text>" + frames + "</office:text>" + xml.substring(bodyOpenEnd + 1)
        }
        val candidates = listOf("<text:p", "<text:h", "<table:table", "<text:list", "<text:section", "<draw:", "<text:soft-page-break", "</office:text>")
        val at = candidates.map { xml.indexOf(it, bodyOpenEnd) }.filter { it >= 0 }.minOrNull() ?: error("office:text slutar inte")
        return xml.substring(0, at) + frames + xml.substring(at)
    }
}
