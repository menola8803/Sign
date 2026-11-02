package se.snc.sign

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Kontaktlistan: namn, e-post och telefon. Sparas privat i appen. */
data class Contact(val id: Long, val name: String, val email: String, val phone: String)

object Contacts {

    fun all(ctx: Context): List<Contact> {
        val arr = runCatching { JSONArray(Prefs.contactsJson(ctx)) }.getOrDefault(JSONArray())
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Contact(o.optLong("id"), o.optString("name"), o.optString("email"), o.optString("phone"))
        }.sortedBy { it.name.lowercase() }
    }

    fun save(ctx: Context, c: Contact) {
        val list = all(ctx).filter { it.id != c.id } + c
        write(ctx, list)
    }

    fun delete(ctx: Context, id: Long) = write(ctx, all(ctx).filter { it.id != id })

    private fun write(ctx: Context, list: List<Contact>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name).put("email", it.email).put("phone", it.phone)) }
        Prefs.setContactsJson(ctx, arr.toString())
    }
}
