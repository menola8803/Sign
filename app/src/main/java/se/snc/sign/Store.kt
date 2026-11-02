package se.snc.sign

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

/** Filer som appen sparar privat: fotot, egna stämplar och signaturer. Inget lämnar telefonen. */
object Store {

    fun photoFile(ctx: Context) = File(ctx.filesDir, "photo.jpg")
    fun hasPhoto(ctx: Context) = photoFile(ctx).exists()
    fun photo(ctx: Context): Bitmap? = photoFile(ctx).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }

    private fun stampDir(ctx: Context) = File(ctx.filesDir, "stamps").apply { mkdirs() }
    fun signatureDir(ctx: Context) = File(ctx.cacheDir, "signatures").apply { mkdirs() }

    data class Stamp(val id: String, val name: String, val builtin: Boolean)

    fun stamps(ctx: Context): List<Stamp> =
        listOf(
            Stamp(Seal.LOGO_ID, "S.N.C – Scandinavian Nexum Consulting", true),
            Stamp(Seal.ID, "Scandinavian Nexum Consulting AB", true),
        ) +
            (stampDir(ctx).listFiles()?.filter { it.extension == "png" }?.sortedBy { it.lastModified() } ?: emptyList())
                .map { Stamp(it.nameWithoutExtension, it.nameWithoutExtension.substringAfter('_').replace('-', ' '), false) }

    fun stampBitmap(ctx: Context, id: String): Bitmap? =
        if (id == Seal.LOGO_ID) Seal.logo(ctx)
        else if (id == Seal.ID) Seal.bitmap(ctx)
        else File(stampDir(ctx), "$id.png").takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }

    /** Läser in en bild som ny stämpel, nedskalad till högst 900 px. */
    fun addStamp(ctx: Context, uri: Uri, displayName: String?): Boolean {
        val bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return false
        val scaled = scaleDown(bmp, 900)
        val safe = (displayName ?: "stamp").substringBeforeLast('.').replace(Regex("[^A-Za-z0-9åäöÅÄÖ]+"), "-").take(40)
        val f = File(stampDir(ctx), "${System.currentTimeMillis()}_$safe.png")
        FileOutputStream(f).use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return true
    }

    fun deleteStamp(ctx: Context, id: String) { File(stampDir(ctx), "$id.png").delete() }

    fun scaleDown(b: Bitmap, max: Int): Bitmap {
        val s = minOf(1f, max.toFloat() / maxOf(b.width, b.height))
        return if (s >= 1f) b else Bitmap.createScaledBitmap(b, (b.width * s).toInt().coerceAtLeast(1), (b.height * s).toInt().coerceAtLeast(1), true)
    }

    fun savePng(b: Bitmap, f: File): File {
        FileOutputStream(f).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return f
    }
}
