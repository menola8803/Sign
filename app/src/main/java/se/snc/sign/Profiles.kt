package se.snc.sign

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.content.edit
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Signatur 1, 2 och 3. Alla har samma uppsättning inställningar:
 * senaste signatur (med tid), foto (med tid), visa namn, visa datum, foto bredvid, kamera och vem som signerar.
 *
 * - Signatur 1 är alltid på och ritas på skärmen.
 * - Signatur 2 är ett tillval (av som standard): fotas på papper eller infogas från Download.
 * - Signatur 3 är dold tills den låses upp med 7 tryck på "Utvecklare". Stängs den av döljs den igen.
 *   Sparade uppgifter finns kvar när den är dold.
 */
class Profile(private val ctx: Context, val n: Int) {

    private val sp = ctx.getSharedPreferences("profile$n", Context.MODE_PRIVATE)
    private val dir = File(ctx.filesDir, "profiles/$n").apply { mkdirs() }

    val sigFile get() = File(dir, "signature.png")
    val photoFile get() = File(dir, "photo.jpg")
    val hasSignature get() = sigFile.exists()
    val hasPhoto get() = photoFile.exists()

    /** Signatur 1 är alltid på. 2 och 3 slås på i Inställningar. */
    var enabled: Boolean
        get() = n == 1 || sp.getBoolean("enabled", false)
        set(v) = sp.edit { putBoolean("enabled", v) }

    /** Bara signatur 3: upplåst med 7 tryck. */
    var unlocked: Boolean
        get() = n != 3 || sp.getBoolean("unlocked", false)
        set(v) = sp.edit { putBoolean("unlocked", v) }

    /** Syns i Inställningar och i "Vem signerar?". */
    val visible get() = unlocked
    val usable get() = visible && enabled

    var showName: Boolean
        get() = sp.getBoolean("show_name", true)
        set(v) = sp.edit { putBoolean("show_name", v) }

    var showDate: Boolean
        get() = sp.getBoolean("show_date", true)
        set(v) = sp.edit { putBoolean("show_date", v) }

    var showPhoto: Boolean
        get() = sp.getBoolean("show_photo", n == 1)
        set(v) = sp.edit { putBoolean("show_photo", v) }

    /** Främre kameran (mot ansiktet) är standard, både för foto och signatur på papper. */
    var camera: String
        get() = sp.getString("camera", Camera.FRONT) ?: Camera.FRONT
        set(v) = sp.edit { putString("camera", v) }

    var signerName: String
        get() = sp.getString("name", "") ?: ""
        set(v) = sp.edit { putString("name", v.trim()) }
    var signerEmail: String
        get() = sp.getString("email", "") ?: ""
        set(v) = sp.edit { putString("email", v.trim()) }
    var signerPhone: String
        get() = sp.getString("phone", "") ?: ""
        set(v) = sp.edit { putString("phone", v.trim()) }

    /** När och hur signaturen skapades: drawn, photographed eller inserted. */
    var sigAt: Long
        get() = sp.getLong("sig_at", 0L)
        private set(v) = sp.edit { putLong("sig_at", v) }
    var sigSource: String
        get() = sp.getString("sig_source", SOURCE_DRAWN) ?: SOURCE_DRAWN
        private set(v) = sp.edit { putString("sig_source", v) }
    var photoAt: Long
        get() = sp.getLong("photo_at", 0L)
        private set(v) = sp.edit { putLong("photo_at", v) }

    fun signature(): Bitmap? = sigFile.takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
    fun photo(): Bitmap? = photoFile.takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }

    fun saveSignature(b: Bitmap, source: String) {
        FileOutputStream(sigFile).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        Composer.forget(sigFile.path)
        sigAt = System.currentTimeMillis(); sigSource = source
    }

    fun saveSignatureFile(f: File, source: String) {
        f.copyTo(sigFile, overwrite = true)
        Composer.forget(sigFile.path)
        sigAt = System.currentTimeMillis(); sigSource = source
    }

    fun savePhoto(b: Bitmap) {
        FileOutputStream(photoFile).use { b.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        Composer.forget(photoFile.path)
        photoAt = System.currentTimeMillis()
    }

    fun deleteSignature() { sigFile.delete(); Composer.forget(sigFile.path); sp.edit { remove("sig_at") } }
    fun deletePhoto() { photoFile.delete(); Composer.forget(photoFile.path); sp.edit { remove("photo_at") } }

    /** Namnet som visas i listor: "Signatur 1 – Claes Sundman". */
    fun label(c: Context): String {
        val who = signerName.ifBlank { c.getString(R.string.no_name_yet) }
        return c.tr(R.string.profile_label, n, who)
    }

    companion object {
        const val SOURCE_DRAWN = "drawn"
        const val SOURCE_PHOTO = "photographed"
        const val SOURCE_FILE = "inserted"

        fun all(ctx: Context) = (1..3).map { Profile(ctx, it) }
        fun usable(ctx: Context) = all(ctx).filter { it.usable }

        /** Datum och tid, t.ex. "4 nov 2026 · 05:12". */
        fun stamp(ctx: Context, millis: Long): String {
            if (millis <= 0L) return ""
            val f = DateTimeFormatter.ofPattern("d MMM yyyy · HH:mm", Lang.locale(ctx)).withZone(ZoneId.systemDefault())
            return f.format(Instant.ofEpochMilli(millis))
        }

        /**
         * Flyttar uppgifterna från version 1.1 (en signatur, ett foto, signatur 2) till profilerna.
         * Körs en gång.
         */
        fun migrate(ctx: Context) {
            val flags = ctx.getSharedPreferences("migrations", Context.MODE_PRIVATE)
            if (flags.getBoolean("profiles_v12", false)) return
            val old = ctx.getSharedPreferences("sign", Context.MODE_PRIVATE)
            val p1 = Profile(ctx, 1)
            p1.signerName = old.getString("name", "") ?: ""
            p1.showName = old.getBoolean("show_name", true)
            p1.showPhoto = old.getBoolean("include_photo", true)
            val oldPhoto = File(ctx.filesDir, "photo.jpg")
            if (oldPhoto.exists()) {
                oldPhoto.copyTo(p1.photoFile, overwrite = true)
                p1.sp.edit { putLong("photo_at", oldPhoto.lastModified()) }
            }
            val p2 = Profile(ctx, 2)
            p2.enabled = old.getBoolean("sig2_enabled", false)
            p2.signerName = old.getString("sig2_name", "") ?: ""
            p2.showDate = old.getBoolean("sig2_date", true)
            p2.showPhoto = false
            val oldSig2 = File(ctx.filesDir, "signature2.png")
            if (oldSig2.exists()) {
                oldSig2.copyTo(p2.sigFile, overwrite = true)
                p2.sp.edit { putLong("sig_at", oldSig2.lastModified()); putString("sig_source", SOURCE_PHOTO) }
            }
            flags.edit { putBoolean("profiles_v12", true) }
        }
    }
}
