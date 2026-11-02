package se.snc.sign

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.util.Patterns
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** Kontaktlistan: lägg till, ändra och ta bort kontakter med namn, e-post och telefon. */
class ContactsActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_contacts)
        fadedTopScreen(findViewById(R.id.contactsRoot), findViewById(R.id.scroll), findViewById(R.id.statusBg), findViewById(R.id.topScrim))
        backTitle(findViewById(R.id.btnBack), getString(R.string.contacts))
        findViewById<View>(R.id.btnAddContact).setOnClickListener { edit(null) }
        render()
    }

    private fun render() {
        val list = findViewById<LinearLayout>(R.id.contactList)
        list.removeAllViews()
        val all = Contacts.all(this)
        findViewById<View>(R.id.contactsEmpty).visibility = if (all.isEmpty()) View.VISIBLE else View.GONE
        all.forEachIndexed { i, c ->
            if (i > 0) list.addView(View(this).apply {
                setBackgroundResource(R.color.line)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1))
            val row = layoutInflater.inflate(R.layout.item_contact, list, false)
            row.findViewById<TextView>(R.id.cInitial).text = c.name.trim().take(1).uppercase()
            row.findViewById<TextView>(R.id.cName).text = c.name
            row.findViewById<TextView>(R.id.cDetails).text = listOf(c.email, c.phone).filter { it.isNotBlank() }.joinToString(" · ")
            row.setOnClickListener { actions(c) }
            list.addView(row)
        }
    }

    private fun actions(c: Contact) {
        val items = ArrayList<Pair<String, () -> Unit>>()
        if (c.email.isNotBlank()) items += getString(R.string.contact_email) to {
            startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + Uri.encode(c.email))))
        }
        if (c.phone.isNotBlank()) items += getString(R.string.contact_call) to {
            startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(c.phone))))
        }
        items += getString(R.string.contact_edit) to { edit(c) }
        items += getString(R.string.remove) to {
            AlertDialog.Builder(this).setMessage(tr(R.string.contact_remove_confirm, c.name))
                .setPositiveButton(R.string.remove) { _, _ -> Contacts.delete(this, c.id); render() }
                .setNegativeButton(R.string.cancel, null).show()
        }
        AlertDialog.Builder(this).setTitle(c.name)
            .setItems(items.map { it.first }.toTypedArray()) { _, i -> runCatching { items[i].second() } }
            .show()
    }

    private fun edit(existing: Contact?) {
        val d = resources.displayMetrics.density
        fun field(hint: Int, type: Int, value: String) = EditText(this).apply {
            setHint(hint); inputType = type; setText(value); setSingleLine()
            setTextColor(getColor(R.color.ink)); setHintTextColor(getColor(R.color.dim))
        }
        val name = field(R.string.contact_name, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PERSON_NAME or InputType.TYPE_TEXT_FLAG_CAP_WORDS, existing?.name.orEmpty())
        val email = field(R.string.contact_email_hint, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, existing?.email.orEmpty())
        val phone = field(R.string.contact_phone_hint, InputType.TYPE_CLASS_PHONE, existing?.phone.orEmpty())
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (20 * d).toInt(); setPadding(p, (8 * d).toInt(), p, 0)
            addView(name); addView(email); addView(phone)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) R.string.contact_add else R.string.contact_edit)
            .setView(box)
            .setPositiveButton(R.string.save_to_device, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val n = name.text.toString().trim()
                val e = email.text.toString().trim()
                when {
                    n.isEmpty() -> name.error = getString(R.string.contact_need_name)
                    e.isNotEmpty() && !Patterns.EMAIL_ADDRESS.matcher(e).matches() -> email.error = getString(R.string.contact_bad_email)
                    else -> {
                        Contacts.save(this, Contact(existing?.id ?: System.currentTimeMillis(), n, e, phone.text.toString().trim()))
                        dialog.dismiss()
                        render()
                        Toast.makeText(this, R.string.contact_saved, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        dialog.show()
    }
}
