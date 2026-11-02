package se.snc.sign

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File

/** Sidorna i ett inläst dokument. Storlek i punkter (pt) som sidan visas, alltså efter rotation. */
interface Pages {
    val count: Int
    fun widthPt(i: Int): Float
    fun heightPt(i: Int): Float
    fun render(i: Int, widthPx: Int): Bitmap
    fun close()
}

/** PDF via Androids inbyggda PdfRenderer. */
class PdfPages(file: File) : Pages {
    private val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(fd)
    private val sizes: List<Pair<Int, Int>> = (0 until renderer.pageCount).map { i ->
        renderer.openPage(i).use { it.width to it.height }
    }
    override val count get() = sizes.size
    override fun widthPt(i: Int) = sizes[i].first.toFloat()
    override fun heightPt(i: Int) = sizes[i].second.toFloat()

    override fun render(i: Int, widthPx: Int): Bitmap = synchronized(this) {
        val w = widthPx.coerceIn(1, 2400)
        val h = (w * heightPt(i) / widthPt(i)).toInt().coerceAtLeast(1)
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        b.eraseColor(Color.WHITE)
        renderer.openPage(i).use { it.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
        b
    }

    override fun close() = synchronized(this) {
        runCatching { renderer.close() }
        runCatching { fd.close() }
        Unit
    }
}

/** Word-sidor som renderats till bilder (en JPEG per sida). [anchors] = stycket mitt på varje sida. */
class ImagePages(private val files: List<File>, private val sizesPt: List<Pair<Float, Float>>, val anchors: List<Int>) : Pages {
    override val count get() = files.size
    override fun widthPt(i: Int) = sizesPt[i].first
    override fun heightPt(i: Int) = sizesPt[i].second
    fun file(i: Int) = files[i]

    override fun render(i: Int, widthPx: Int): Bitmap {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(files[i].path, opts)
        var sample = 1
        while (opts.outWidth / (sample * 2) >= widthPx) sample *= 2
        val b = BitmapFactory.decodeFile(files[i].path, BitmapFactory.Options().apply { inSampleSize = sample })
        return if (b.width > widthPx * 1.2f) Bitmap.createScaledBitmap(b, widthPx, (b.height * widthPx.toFloat() / b.width).toInt(), true) else b
    }

    override fun close() {}
}
