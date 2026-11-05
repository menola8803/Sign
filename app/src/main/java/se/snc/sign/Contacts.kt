package se.snc.sign

import android.Manifest
import android.content.ContentProviderOperation
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Organization
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

/**
 * En kontakt. lookup != null betyder att den är kopplad till telefonens kontaktlista.
 */
data class Contact(
    val id: Long, val name: String, val email: String, val phone: String,
    val company: String = "", val lookup: String? = null,
) {
    val linked get() = lookup != null
    val key get() = lookup ?: "local:$id"
}

/** Kontakter som bara finns i Sign (sparas privat i appen). */
object Contacts {

    fun all(ctx: Context): List<Contact> {
        val arr = runCatching { JSONArray(Prefs.contactsJson(ctx)) }.getOrDefault(JSONArray())
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Contact(o.optLong("id"), o.optString("name"), o.optString("email"), o.optString("phone"), o.optString("company"))
        }.sortedBy { it.name.lowercase() }
    }

    fun save(ctx: Context, c: Contact) = write(ctx, all(ctx).filter { it.id != c.id } + c.copy(lookup = null))

    fun delete(ctx: Context, id: Long) = write(ctx, all(ctx).filter { it.id != id })

    private fun write(ctx: Context, list: List<Contact>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name).put("email", it.email).put("phone", it.phone).put("company", it.company)) }
        Prefs.setContactsJson(ctx, arr.toString())
    }
}

/**
 * Kontaktboken som resten av appen använder: telefonens kontakter (med e-post eller telefonnummer)
 * plus kontakter som bara finns i Sign. Synken går åt båda hållen:
 * ändringar i telefonen syns direkt i Sign, och ändringar i Sign sparas i telefonen.
 */
object ContactBook {

    private fun sp(ctx: Context) = ctx.getSharedPreferences("contacts", Context.MODE_PRIVATE)

    fun hasPermission(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** Användarens val i Inställningar. Synken används bara om behörigheten också finns. */
    fun wantSync(ctx: Context) = sp(ctx).getBoolean("sync", true)
    fun setWantSync(ctx: Context, on: Boolean) = sp(ctx).edit { putBoolean("sync", on) }
    fun asked(ctx: Context) = sp(ctx).getBoolean("asked", false)
    fun setAsked(ctx: Context) = sp(ctx).edit { putBoolean("asked", true) }
    fun syncing(ctx: Context) = wantSync(ctx) && hasPermission(ctx)

    /** Telefonkontakter som tagits bort "bara från Sign". */
    private fun hidden(ctx: Context): Set<String> = sp(ctx).getStringSet("hidden", emptySet()) ?: emptySet()
    fun hide(ctx: Context, lookup: String) = sp(ctx).edit { putStringSet("hidden", hidden(ctx) + lookup) }
    fun unhide(ctx: Context, lookup: String) = sp(ctx).edit { putStringSet("hidden", hidden(ctx) - lookup) }

    fun all(ctx: Context): List<Contact> {
        val local = Contacts.all(ctx)
        if (!syncing(ctx)) return local
        val phone = runCatching { PhoneContacts.query(ctx) }.getOrDefault(emptyList()).filter { it.lookup !in hidden(ctx) }
        val emails = phone.map { it.email.lowercase() }.filter { it.isNotBlank() }.toSet()
        val merged = phone + local.filter { it.email.isBlank() || it.email.lowercase() !in emails }
        return merged.sortedBy { it.name.lowercase() }
    }

    fun find(ctx: Context, key: String) = all(ctx).find { it.key == key }

    /** Sparar en ny eller ändrad kontakt. Med synk sparas den i telefonens kontakter. */
    fun save(ctx: Context, c: Contact): Contact {
        if (syncing(ctx)) {
            return if (c.lookup != null) { PhoneContacts.update(ctx, c); c }
            else {
                if (c.id > 0 && Contacts.all(ctx).any { it.id == c.id }) Contacts.delete(ctx, c.id)
                PhoneContacts.insert(ctx, c) ?: c.also { Contacts.save(ctx, it) }
            }
        }
        Contacts.save(ctx, c)
        return c
    }

    /** alsoPhone = ta bort även från telefonens kontakter; annars döljs den bara i Sign. */
    fun delete(ctx: Context, c: Contact, alsoPhone: Boolean) {
        if (c.lookup != null) {
            if (alsoPhone) PhoneContacts.delete(ctx, c.lookup) else hide(ctx, c.lookup)
        } else Contacts.delete(ctx, c.id)
    }

    /** Ångra: lägger tillbaka en kontakt som just tagits bort. */
    fun restore(ctx: Context, c: Contact, wasPhoneDeleted: Boolean) {
        if (c.lookup != null) {
            if (wasPhoneDeleted) PhoneContacts.insert(ctx, c.copy(lookup = null)) else unhide(ctx, c.lookup)
        } else Contacts.save(ctx, c)
    }
}

/** Läser och skriver telefonens kontakter via ContactsContract. */
object PhoneContacts {

    fun query(ctx: Context): List<Contact> {
        val map = LinkedHashMap<Long, Array<String>>() // id -> [lookup, name, email, phone, company]
        ctx.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data.CONTACT_ID, ContactsContract.Data.LOOKUP_KEY, ContactsContract.Data.DISPLAY_NAME_PRIMARY, ContactsContract.Data.MIMETYPE, ContactsContract.Data.DATA1),
            "${ContactsContract.Data.MIMETYPE} IN (?,?,?)",
            arrayOf(Email.CONTENT_ITEM_TYPE, Phone.CONTENT_ITEM_TYPE, Organization.CONTENT_ITEM_TYPE),
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val row = map.getOrPut(id) { arrayOf(c.getString(1) ?: "", c.getString(2) ?: "", "", "", "") }
                val v = c.getString(4) ?: ""
                when (c.getString(3)) {
                    Email.CONTENT_ITEM_TYPE -> if (row[2].isBlank()) row[2] = v
                    Phone.CONTENT_ITEM_TYPE -> if (row[3].isBlank()) row[3] = v
                    Organization.CONTENT_ITEM_TYPE -> if (row[4].isBlank()) row[4] = v
                }
            }
        }
        return map.filter { (_, r) -> r[1].isNotBlank() && (r[2].isNotBlank() || r[3].isNotBlank()) }
            .map { (id, r) -> Contact(id, r[1], r[2], r[3], r[4], r[0]) }
    }

    private fun rawId(ctx: Context, contactId: Long): Long? =
        ctx.contentResolver.query(
            ContactsContract.RawContacts.CONTENT_URI, arrayOf(ContactsContract.RawContacts._ID),
            "${ContactsContract.RawContacts.CONTACT_ID}=?", arrayOf(contactId.toString()), null,
        )?.use { if (it.moveToFirst()) it.getLong(0) else null }

    private fun dataId(ctx: Context, rawId: Long, mime: String): Long? =
        ctx.contentResolver.query(
            ContactsContract.Data.CONTENT_URI, arrayOf(ContactsContract.Data._ID),
            "${ContactsContract.Data.RAW_CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?", arrayOf(rawId.toString(), mime), null,
        )?.use { if (it.moveToFirst()) it.getLong(0) else null }

    fun update(ctx: Context, c: Contact) {
        val raw = rawId(ctx, c.id) ?: return
        val ops = ArrayList<ContentProviderOperation>()
        fun upsert(mime: String, values: Map<String, String?>) {
            val existing = dataId(ctx, raw, mime)
            val b = if (existing != null)
                ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI).withSelection("${ContactsContract.Data._ID}=?", arrayOf(existing.toString()))
            else
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI).withValue(ContactsContract.Data.RAW_CONTACT_ID, raw).withValue(ContactsContract.Data.MIMETYPE, mime)
            values.forEach { (k, v) -> b.withValue(k, v) }
            ops += b.build()
        }
        upsert(StructuredName.CONTENT_ITEM_TYPE, mapOf(
            StructuredName.DISPLAY_NAME to c.name, StructuredName.GIVEN_NAME to null, StructuredName.FAMILY_NAME to null,
            StructuredName.MIDDLE_NAME to null, StructuredName.PREFIX to null, StructuredName.SUFFIX to null,
        ))
        if (c.email.isNotBlank()) upsert(Email.CONTENT_ITEM_TYPE, mapOf(Email.ADDRESS to c.email, Email.TYPE to Email.TYPE_WORK.toString()))
        if (c.phone.isNotBlank()) upsert(Phone.CONTENT_ITEM_TYPE, mapOf(Phone.NUMBER to c.phone, Phone.TYPE to Phone.TYPE_MOBILE.toString()))
        if (c.company.isNotBlank()) upsert(Organization.CONTENT_ITEM_TYPE, mapOf(Organization.COMPANY to c.company))
        ctx.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
    }

    fun insert(ctx: Context, c: Contact): Contact? {
        val ops = ArrayList<ContentProviderOperation>()
        ops += ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
            .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
            .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null).build()
        fun add(mime: String, values: Map<String, String>) {
            val b = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, mime)
            values.forEach { (k, v) -> b.withValue(k, v) }
            ops += b.build()
        }
        add(StructuredName.CONTENT_ITEM_TYPE, mapOf(StructuredName.DISPLAY_NAME to c.name))
        if (c.email.isNotBlank()) add(Email.CONTENT_ITEM_TYPE, mapOf(Email.ADDRESS to c.email, Email.TYPE to Email.TYPE_WORK.toString()))
        if (c.phone.isNotBlank()) add(Phone.CONTENT_ITEM_TYPE, mapOf(Phone.NUMBER to c.phone, Phone.TYPE to Phone.TYPE_MOBILE.toString()))
        if (c.company.isNotBlank()) add(Organization.CONTENT_ITEM_TYPE, mapOf(Organization.COMPANY to c.company))
        val res = runCatching { ctx.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops) }.getOrNull() ?: return null
        val rawUri = res.firstOrNull()?.uri ?: return null
        val contactId = ctx.contentResolver.query(rawUri, arrayOf(ContactsContract.RawContacts.CONTACT_ID), null, null, null)
            ?.use { if (it.moveToFirst()) it.getLong(0) else null } ?: return null
        return query(ctx).find { it.id == contactId }
    }

    fun delete(ctx: Context, lookup: String) {
        ctx.contentResolver.delete(Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_LOOKUP_URI, lookup), null, null)
    }
}
