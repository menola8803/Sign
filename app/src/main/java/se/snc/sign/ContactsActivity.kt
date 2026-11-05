package se.snc.sign

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.text.InputType
import android.util.Patterns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Kontakter: A–Ö med sök, snabbknappar för e-post och samtal, svep för att ta bort (med Ångra) och flerval.
 * Med synk visas telefonens kontakter (med e-post eller nummer) och ändringar sparas åt båda hållen.
 */
class ContactsActivity : BaseActivity() {

    private sealed class Item {
        data class Header(val letter: String) : Item()
        data class Row(val c: Contact) : Item()
    }

    private var all = emptyList<Contact>()
    private var items = emptyList<Item>()
    private var query = ""
    private val selected = LinkedHashSet<String>()
    private lateinit var list: RecyclerView
    private lateinit var banner: View
    private lateinit var selectBar: LinearLayout
    private lateinit var selectText: TextView
    private lateinit var undoBar: LinearLayout
    private lateinit var undoText: TextView
    private var undo: (() -> Unit)? = null
    private val hideUndo = Runnable { undoBar.visibility = View.GONE; undo = null }

    private val permission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        ContactBook.setAsked(this)
        if (!ContactBook.hasPermission(this)) Toast.makeText(this, R.string.contacts_local_only, Toast.LENGTH_LONG).show()
        reload()
    }

    /** Hämta en kontakt från telefonen utan att ge appen tillgång till hela kontaktlistan. */
    private val pickPhone = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val uri = r.data?.data ?: return@registerForActivityResult
        val c = runCatching {
            contentResolver.query(uri, arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)
                ?.use { if (it.moveToFirst()) Contact(System.currentTimeMillis(), it.getString(0) ?: "", "", it.getString(1) ?: "") else null }
        }.getOrNull()
        ContactEditor.show(this, c ?: return@registerForActivityResult, isNew = true) { reload() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val c = this
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val root = FrameLayout(c).apply { setBackgroundColor(Kit.color(c, R.color.bg)) }
        val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        root.addView(col, FrameLayout.LayoutParams(-1, -1))
        val head = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(4)) }
        col.addView(head)
        val top = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(Kit.text(c, "‹  " + getString(R.string.contacts), 20f, isBold = true).apply {
            minHeight = dp(48); gravity = Gravity.CENTER_VERTICAL; setBackgroundResource(R.drawable.bg_row); setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(Kit.button(c, "+ " + getString(R.string.contact_add), primary = true) { addContact() })
        head.addView(top)

        // Fråga en gång om telefonens kontakter får visas
        banner = Kit.card(c, 12).apply {
            addView(Kit.text(c, getString(R.string.contacts_sync_title), 15f, isBold = true))
            addView(Kit.text(c, getString(R.string.contacts_sync_text), 13f, R.color.muted), Kit.lp(c, top = 4))
            addView(Kit.buttonRow(c,
                Kit.button(c, getString(R.string.not_now)) { ContactBook.setAsked(c); reload() },
                Kit.button(c, getString(R.string.allow), primary = true) {
                    permission.launch(arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS))
                }), Kit.lp(c, top = 10))
        }
        head.addView(banner, Kit.lp(c, top = 8))
        head.addView(Kit.input(c, "🔍  " + getString(R.string.search), "", InputType.TYPE_CLASS_TEXT) { query = it; render() }, Kit.lp(c, top = 10))

        list = RecyclerView(c).apply {
            layoutManager = LinearLayoutManager(c); adapter = Adapter(); clipToPadding = false
            setPadding(dp(16), dp(6), dp(16), dp(90))
        }
        col.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun getSwipeDirs(rv: RecyclerView, vh: RecyclerView.ViewHolder) =
                if (items.getOrNull(vh.bindingAdapterPosition) is Item.Row && selected.isEmpty()) super.getSwipeDirs(rv, vh) else 0
            override fun onMove(rv: RecyclerView, a: RecyclerView.ViewHolder, b: RecyclerView.ViewHolder) = false
            override fun onSwiped(vh: RecyclerView.ViewHolder, dir: Int) {
                val row = items.getOrNull(vh.bindingAdapterPosition) as? Item.Row ?: return
                askDelete(listOf(row.c))
            }
        }).attachToRecyclerView(list)

        // Flerval
        selectBar = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_sheet); setPadding(dp(16), dp(10), dp(16), dp(10)); visibility = View.GONE
        }
        selectText = Kit.text(c, "", 15f, isBold = true)
        selectBar.addView(selectText, LinearLayout.LayoutParams(0, -2, 1f))
        selectBar.addView(Kit.button(c, getString(R.string.cancel)) { selected.clear(); render() })
        selectBar.addView(Kit.button(c, getString(R.string.delete), danger = true) {
            askDelete(all.filter { it.key in selected })
        }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        root.addView(selectBar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        // Ångra efter borttagning
        undoBar = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_sheet); setPadding(dp(16), dp(8), dp(10), dp(8)); visibility = View.GONE
        }
        undoText = Kit.text(c, "", 14f)
        undoBar.addView(undoText, LinearLayout.LayoutParams(0, -2, 1f))
        undoBar.addView(Kit.text(c, getString(R.string.undo), 15f, R.color.gold, isBold = true).apply {
            setPadding(dp(14), dp(10), dp(14), dp(10)); setBackgroundResource(R.drawable.bg_row)
            setOnClickListener { undo?.invoke(); undoBar.removeCallbacks(hideUndo); hideUndo.run(); reload() }
        })
        root.addView(undoBar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        val statusBg = View(c).apply { setBackgroundColor(0xFF000000.toInt()) }
        root.addView(statusBg, FrameLayout.LayoutParams(-1, 0))
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            statusBg.updateLayoutParams<ViewGroup.LayoutParams> { height = b.top }
            col.updatePadding(top = b.top, left = b.left, right = b.right)
            selectBar.updatePadding(bottom = dp(10) + b.bottom); undoBar.updatePadding(bottom = dp(8) + b.bottom)
            list.updatePadding(bottom = dp(90) + b.bottom)
            insets
        }
    }

    override fun onResume() { super.onResume(); reload() }

    private fun reload() {
        banner.visibility = if (!ContactBook.asked(this) && ContactBook.wantSync(this) && !ContactBook.hasPermission(this)) View.VISIBLE else View.GONE
        all = runCatching { ContactBook.all(this) }.getOrDefault(Contacts.all(this))
        selected.retainAll(all.map { it.key }.toSet())
        render()
    }

    private fun render() {
        val q = query.trim().lowercase()
        val shown = all.filter { q.isEmpty() || it.name.lowercase().contains(q) || it.email.lowercase().contains(q) || it.phone.contains(q) || it.company.lowercase().contains(q) }
        val out = ArrayList<Item>()
        var last = ""
        for (c in shown) {
            val l = c.name.trim().take(1).uppercase().ifBlank { "#" }.let { if (it[0].isLetter()) it else "#" }
            if (l != last) { out += Item.Header(l); last = l }
            out += Item.Row(c)
        }
        items = out
        list.adapter?.notifyDataSetChanged()
        selectBar.visibility = if (selected.isEmpty()) View.GONE else View.VISIBLE
        selectText.text = tr(R.string.n_selected, selected.size)
        if (shown.isEmpty()) {
            items = listOf(Item.Header(getString(if (all.isEmpty()) R.string.no_contacts else R.string.no_matches)))
            list.adapter?.notifyDataSetChanged()
        }
    }

    private fun addContact() {
        Sheet(this).title(getString(R.string.contact_add))
            .row("📱", getString(R.string.import_from_phone), getString(R.string.import_from_phone_sub)) {
                pickPhone.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI))
            }
            .row("✎", getString(R.string.fill_in), getString(R.string.fill_in_sub)) {
                ContactEditor.show(this, null, isNew = true) { reload() }
            }
            .show()
    }

    /** Kopplade kontakter: "Bara från Sign" (standard) eller "Även från telefonen". */
    private fun askDelete(contacts: List<Contact>) {
        if (contacts.isEmpty()) return
        val linked = contacts.any { it.linked }
        val done = { alsoPhone: Boolean ->
            contacts.forEach { ContactBook.delete(this, it, alsoPhone) }
            selected.clear()
            showUndo(if (contacts.size == 1) tr(R.string.contact_deleted, contacts[0].name) else tr(R.string.contacts_deleted, contacts.size)) {
                contacts.forEach { ContactBook.restore(this, it, alsoPhone) }
            }
            reload()
        }
        val title = if (contacts.size == 1) contacts[0].name else tr(R.string.n_selected, contacts.size)
        val s = Sheet(this).title(getString(R.string.delete_contact_title), title)
        if (linked && ContactBook.syncing(this)) {
            s.row("✓", getString(R.string.delete_only_sign), getString(R.string.delete_only_sign_sub)) { done(false) }
            s.row("📱", getString(R.string.delete_also_phone), getString(R.string.delete_also_phone_sub), danger = true) { done(true) }
            s.buttons(getString(R.string.cancel), null)
        } else s.buttons(getString(R.string.cancel), getString(R.string.delete), { done(false) }, danger = true)
        s.dialog.setOnDismissListener { list.adapter?.notifyDataSetChanged() }
        s.show()
    }

    private fun showUndo(text: String, action: () -> Unit) {
        undo = action
        undoText.text = text
        undoBar.visibility = View.VISIBLE
        undoBar.removeCallbacks(hideUndo)
        undoBar.postDelayed(hideUndo, 6000)
    }

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = items.size
        override fun getItemViewType(position: Int) = if (items[position] is Item.Header) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val c = this@ContactsActivity
            val v = if (viewType == 0) Kit.text(c, "", 13f, R.color.gold, isBold = true).apply {
                val d = resources.displayMetrics.density; setPadding((4 * d).toInt(), (14 * d).toInt(), 0, (4 * d).toInt())
            } else layoutInflater.inflate(R.layout.item_contact, parent, false)
            v.layoutParams = RecyclerView.LayoutParams(-1, -2)
            return object : RecyclerView.ViewHolder(v) {}
        }

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
            when (val it = items[position]) {
                is Item.Header -> (h.itemView as TextView).text = it.letter
                is Item.Row -> bindRow(h.itemView, it.c)
            }
        }
    }

    private fun bindRow(v: View, c: Contact) {
        val sel = c.key in selected
        v.findViewById<TextView>(R.id.cInitial).text = if (sel) "✓" else c.name.trim().take(1).uppercase()
        v.findViewById<TextView>(R.id.cName).text = c.name
        v.findViewById<TextView>(R.id.cDetails).text =
            (listOf(c.email, c.phone, c.company).filter { it.isNotBlank() } + (if (c.linked) listOf("📱") else emptyList())).joinToString(" · ")
        v.setBackgroundResource(if (sel) R.drawable.bg_stamp_row else R.drawable.bg_row)
        v.isSelected = sel
        v.findViewById<View?>(R.id.cMail)?.apply {
            visibility = if (c.email.isBlank()) View.INVISIBLE else View.VISIBLE
            setOnClickListener { startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + c.email))) }
        }
        v.findViewById<View?>(R.id.cCall)?.apply {
            visibility = if (c.phone.isBlank()) View.INVISIBLE else View.VISIBLE
            setOnClickListener { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + c.phone))) }
        }
        v.setOnClickListener {
            if (selected.isNotEmpty()) toggle(c)
            else startActivity(Intent(this, ContactActivity::class.java).putExtra(ContactActivity.EXTRA_KEY, c.key))
        }
        v.setOnLongClickListener { toggle(c); true }
    }

    private fun toggle(c: Contact) {
        if (!selected.remove(c.key)) selected.add(c.key)
        render()
    }
}

/** Formuläret för att lägga till eller ändra en kontakt. Namn krävs, e-post kontrolleras. */
object ContactEditor {
    fun show(a: BaseActivity, c: Contact?, isNew: Boolean, onSaved: (Contact) -> Unit) {
        val name = Kit.input(a, a.getString(R.string.contact_name) + " *", c?.name ?: "")
        val email = Kit.input(a, a.getString(R.string.contact_email_hint), c?.email ?: "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        val phone = Kit.input(a, a.getString(R.string.contact_phone_hint), c?.phone ?: "", InputType.TYPE_CLASS_PHONE)
        val company = Kit.input(a, a.getString(R.string.contact_company), c?.company ?: "")
        val error = Kit.text(a, "", 13f, R.color.danger).apply { visibility = View.GONE }
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        box.addView(name); box.addView(email, Kit.lp(a, top = 8)); box.addView(phone, Kit.lp(a, top = 8)); box.addView(company, Kit.lp(a, top = 8))
        box.addView(error, Kit.lp(a, top = 8))
        if (ContactBook.syncing(a)) box.addView(Kit.text(a, a.getString(R.string.saved_to_phone_too), 12f, R.color.dim), Kit.lp(a, top = 8))
        val s = Sheet(a).title(a.getString(if (isNew && c?.lookup == null) R.string.contact_add else R.string.contact_edit)).view(box)
        val save = s.button(a.getString(R.string.save), true) {
            val n = name.text.toString().trim()
            val e = email.text.toString().trim()
            when {
                n.isBlank() -> { error.setText(R.string.contact_need_name); error.visibility = View.VISIBLE }
                e.isNotBlank() && !Patterns.EMAIL_ADDRESS.matcher(e).matches() -> { error.setText(R.string.contact_bad_email); error.visibility = View.VISIBLE }
                else -> {
                    val base = c ?: Contact(System.currentTimeMillis(), "", "", "")
                    val saved = runCatching {
                        ContactBook.save(a, base.copy(id = if (isNew && base.lookup == null) System.currentTimeMillis() else base.id,
                            name = n, email = e, phone = phone.text.toString().trim(), company = company.text.toString().trim()))
                    }.getOrNull()
                    if (saved == null) Toast.makeText(a, R.string.contact_save_failed, Toast.LENGTH_LONG).show()
                    else { Toast.makeText(a, R.string.contact_saved, Toast.LENGTH_SHORT).show(); s.dialog.dismiss(); onSaved(saved) }
                }
            }
        }
        val row = Kit.buttonRow(a, s.button(a.getString(R.string.cancel), false) { s.dialog.dismiss() }, save)
        s.view(row, 14)
        s.show()
    }
}

/** En kontakt: e-post, samtal, meddelande, skicka dokument, signerade dokument, ändra och ta bort. */
class ContactActivity : BaseActivity() {
    companion object { const val EXTRA_KEY = "se.snc.sign.CONTACT_KEY" }

    override fun onResume() {
        super.onResume()
        val c = ContactBook.find(this, intent.getStringExtra(EXTRA_KEY) ?: "") ?: run { finish(); return }
        build(c)
    }

    private fun build(ct: Contact) {
        val a = this
        val screen = Screen(this, getString(R.string.contacts))
        val d = resources.displayMetrics.density
        val head = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        head.addView(Kit.text(a, ct.name.trim().take(1).uppercase(), 30f, R.color.gold, isBold = true).apply {
            gravity = Gravity.CENTER; setBackgroundResource(R.drawable.bg_avatar)
        }, LinearLayout.LayoutParams((84 * d).toInt(), (84 * d).toInt()))
        head.addView(Kit.text(a, ct.name, 22f, isBold = true).apply { gravity = Gravity.CENTER }, Kit.lp(a, top = 10))
        if (ct.company.isNotBlank()) head.addView(Kit.text(a, ct.company, 14f, R.color.muted).apply { gravity = Gravity.CENTER })
        if (ct.linked) head.addView(Kit.text(a, "📱 " + getString(R.string.linked_to_phone), 12f, R.color.dim).apply { gravity = Gravity.CENTER }, Kit.lp(a, top = 4))
        screen.add(head, 8)

        fun quick(icon: String, label: String, enabled: Boolean, go: () -> Unit) = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; alpha = if (enabled) 1f else 0.35f
            setBackgroundResource(R.drawable.bg_card); setPadding(0, (10 * d).toInt(), 0, (10 * d).toInt())
            addView(Kit.text(a, icon, 20f).apply { gravity = Gravity.CENTER })
            addView(Kit.text(a, label, 12f, R.color.muted).apply { gravity = Gravity.CENTER })
            if (enabled) setOnClickListener { runCatching { go() } }
        }
        screen.add(Kit.buttonRow(a,
            quick("✉", getString(R.string.contact_email), ct.email.isNotBlank()) { startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + ct.email))) },
            quick("☎", getString(R.string.contact_call), ct.phone.isNotBlank()) { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + ct.phone))) },
            quick("💬", getString(R.string.contact_message), ct.phone.isNotBlank()) { startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + ct.phone))) },
            quick("📄", getString(R.string.send_doc), ct.email.isNotBlank() || ct.phone.isNotBlank()) { sendDoc(ct) }), 16)

        val info = Kit.card(a)
        fun line(label: String, v: String) {
            if (v.isBlank()) return
            if (info.childCount > 0) info.addView(Kit.divider(a))
            info.addView(Kit.text(a, label, 12.5f, R.color.dim)); info.addView(Kit.text(a, v, 15.5f))
        }
        line(getString(R.string.contact_email_hint), ct.email); line(getString(R.string.contact_phone_hint), ct.phone); line(getString(R.string.contact_company), ct.company)
        if (info.childCount > 0) screen.add(info, 14)

        // Dokument som kontakten har signerat (ändras aldrig i efterhand)
        screen.add(Kit.section(a, getString(R.string.signed_documents)))
        val docs = LibDoc.all(a).filter { doc -> doc.signedBy.any { it.equals(ct.name, true) } || doc.waitingFor.any { it.equals(ct.name, true) } }
        if (docs.isEmpty()) screen.add(Kit.text(a, getString(R.string.no_signed_docs_contact), 13.5f, R.color.dim))
        docs.forEach { doc ->
            screen.add(Kit.row(a, doc.format.label.take(1), doc.name, StatusChip.make(a, doc).text.toString() + " · " + Profile.stamp(a, doc.updated)) {
                startActivity(Intent(a, DocPreviewActivity::class.java).putExtra(DocumentActivity.EXTRA_LIB_ID, doc.id))
            }, 4)
        }

        screen.add(Kit.buttonRow(a,
            Kit.button(a, getString(R.string.contact_edit)) { ContactEditor.show(a, ct, isNew = false) { build(it) } },
            Kit.button(a, getString(R.string.delete), danger = true) { delete(ct) }), 20)
        screen.show()
    }

    private fun sendDoc(ct: Contact) {
        val docs = LibDoc.all(this)
        if (docs.isEmpty()) { Toast.makeText(this, R.string.no_saved_docs, Toast.LENGTH_LONG).show(); return }
        val s = Sheet(this).title(tr(R.string.send_doc_to, ct.name))
        docs.take(30).forEach { doc ->
            s.row(doc.format.label.take(1), doc.name, StatusChip.make(this, doc).text) {
                val (uri, mime) = ShareSheet.shareUri(this, doc)
                val i = Intent(Intent.ACTION_SEND).apply {
                    type = mime; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    if (ct.email.isNotBlank()) putExtra(Intent.EXTRA_EMAIL, arrayOf(ct.email))
                    putExtra(Intent.EXTRA_SUBJECT, tr(R.string.mail_subject, doc.name))
                    putExtra(Intent.EXTRA_TEXT, tr(R.string.mail_body, ct.name, Profile(this@ContactActivity, 1).signerName))
                }
                startActivity(Intent.createChooser(i, getString(R.string.send_doc)))
            }
        }
        s.show()
    }

    private fun delete(ct: Contact) {
        val s = Sheet(this).title(getString(R.string.delete_contact_title), ct.name)
        if (ct.linked && ContactBook.syncing(this)) {
            s.row("✓", getString(R.string.delete_only_sign), getString(R.string.delete_only_sign_sub)) { ContactBook.delete(this, ct, false); finish() }
            s.row("📱", getString(R.string.delete_also_phone), getString(R.string.delete_also_phone_sub), danger = true) { ContactBook.delete(this, ct, true); finish() }
            s.buttons(getString(R.string.cancel), null)
        } else s.buttons(getString(R.string.cancel), getString(R.string.delete), { ContactBook.delete(this, ct, false); finish() }, danger = true)
        s.show()
    }
}
