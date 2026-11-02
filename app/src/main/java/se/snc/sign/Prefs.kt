package se.snc.sign

import android.content.Context
import androidx.core.content.edit

/** Inställningar som sparas privat i appen. Säkerhetskopiering är avstängd i manifestet. */
object Prefs {

    private const val FILE = "sign"
    private const val KEY_LANGUAGE = "language"
    private const val KEY_NAME = "name"
    private const val KEY_SHOW_NAME = "show_name"
    private const val KEY_SHOW_DATE = "show_date"
    private const val KEY_INCLUDE_PHOTO = "include_photo"
    private const val KEY_INK = "ink"
    private const val KEY_AUTO_SEAL = "auto_seal"
    private const val KEY_PHOTO_CAMERA = "photo_camera"
    private const val KEY_SIG2_ENABLED = "sig2_enabled"
    private const val KEY_SIG2_NAME = "sig2_name"
    private const val KEY_SIG2_DATE = "sig2_date"
    private const val KEY_CONTACTS = "contacts"
    private const val KEY_AUTO_UPDATE = "auto_update"
    private const val KEY_LAST_UPDATE_CHECK = "last_update_check"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Engelska är alltid förvalt. Svenska väljs under Inställningar → Språk. */
    fun language(ctx: Context): String = sp(ctx).getString(KEY_LANGUAGE, null) ?: Lang.EN
    fun setLanguage(ctx: Context, tag: String) = sp(ctx).edit { putString(KEY_LANGUAGE, tag) }

    fun name(ctx: Context): String = sp(ctx).getString(KEY_NAME, "") ?: ""
    fun setName(ctx: Context, v: String) = sp(ctx).edit { putString(KEY_NAME, v.trim()) }

    fun showName(ctx: Context) = sp(ctx).getBoolean(KEY_SHOW_NAME, true)
    fun setShowName(ctx: Context, on: Boolean) = sp(ctx).edit { putBoolean(KEY_SHOW_NAME, on) }

    fun showDate(ctx: Context) = sp(ctx).getBoolean(KEY_SHOW_DATE, true)
    fun setShowDate(ctx: Context, on: Boolean) = sp(ctx).edit { putBoolean(KEY_SHOW_DATE, on) }

    fun includePhoto(ctx: Context) = sp(ctx).getBoolean(KEY_INCLUDE_PHOTO, true)
    fun setIncludePhoto(ctx: Context, on: Boolean) = sp(ctx).edit { putBoolean(KEY_INCLUDE_PHOTO, on) }

    /** Bläckfärg för signaturen. */
    fun ink(ctx: Context): Int = sp(ctx).getInt(KEY_INK, Ink.BLUE)
    fun setInk(ctx: Context, c: Int) = sp(ctx).edit { putInt(KEY_INK, c) }

    /** Vilken kamera fotot till signatur 1 tas med: FRONT (mot ansiktet) eller BACK (huvudkameran). */
    fun photoCamera(ctx: Context): String = sp(ctx).getString(KEY_PHOTO_CAMERA, Camera.FRONT) ?: Camera.FRONT
    fun setPhotoCamera(ctx: Context, v: String) = sp(ctx).edit { putString(KEY_PHOTO_CAMERA, v) }

    /** Signatur 2 är ett tillval: av som standard. */
    fun sig2Enabled(ctx: Context) = sp(ctx).getBoolean(KEY_SIG2_ENABLED, false)
    fun setSig2Enabled(ctx: Context, on: Boolean) = sp(ctx).edit { putBoolean(KEY_SIG2_ENABLED, on) }
    fun sig2Name(ctx: Context): String = sp(ctx).getString(KEY_SIG2_NAME, "") ?: ""
    fun setSig2Name(ctx: Context, v: String) = sp(ctx).edit { putString(KEY_SIG2_NAME, v.trim()) }
    fun sig2Date(ctx: Context) = sp(ctx).getBoolean(KEY_SIG2_DATE, true)
    fun setSig2Date(ctx: Context, on: Boolean) = sp(ctx).edit { putBoolean(KEY_SIG2_DATE, on) }

    /** S.N.C-sigillet läggs automatiskt i vänstra hörnet på första sidan. */
    fun autoSeal(ctx: Context) = sp(ctx).getBoolean(KEY_AUTO_SEAL, true)
    fun setAutoSeal(ctx: Context, on: Boolean) = sp(ctx).edit { putBoolean(KEY_AUTO_SEAL, on) }

    fun contactsJson(ctx: Context): String = sp(ctx).getString(KEY_CONTACTS, "[]") ?: "[]"
    fun setContactsJson(ctx: Context, json: String) = sp(ctx).edit { putString(KEY_CONTACTS, json) }

    fun autoUpdate(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_AUTO_UPDATE, true)
    fun setAutoUpdate(ctx: Context, on: Boolean) = sp(ctx).edit { putBoolean(KEY_AUTO_UPDATE, on) }

    fun lastUpdateCheck(ctx: Context): Long = sp(ctx).getLong(KEY_LAST_UPDATE_CHECK, 0L)
    fun setLastUpdateCheck(ctx: Context, ts: Long) = sp(ctx).edit { putLong(KEY_LAST_UPDATE_CHECK, ts) }
}

object Camera {
    const val FRONT = "front"
    const val BACK = "back"
}

object Ink {
    const val BLUE = 0xFF0F2A6B.toInt()
    const val BLACK = 0xFF111111.toInt()
}
