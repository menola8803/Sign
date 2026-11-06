package se.snc.sign

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.res.ResourcesCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.launch

/**
 * Hemskärmen (design A): S.N.C-loggan överst, "Fota dokument" och "Öppna dokument",
 * senaste sparade dokument, Kontakter och Uppdateringar. Kugghjulet uppe till höger.
 */
class MainActivity : BaseActivity() {

    private lateinit var recent: LinearLayout
    private lateinit var savedTitle: TextView
    private lateinit var contactsSub: TextView
    private lateinit var updateDot: View
    private lateinit var updateLine: TextView

    private val openLauncher = registerForActivityResult(OpenInDownloads()) { uri ->
        if (uri != null) startActivity(Intent(this, DocumentActivity::class.java).setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    private val scanLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        if (r.resultCode != RESULT_OK) return@registerForActivityResult
        val pdf = GmsDocumentScanningResult.fromActivityResultIntent(r.data)?.pdf?.uri ?: return@registerForActivityResult
        startActivity(Intent(this, DocumentActivity::class.java).setData(pdf)
            .putExtra(DocumentActivity.EXTRA_SCANNED, true).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        Profile.migrate(this)
        val c = this
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val screen = Screen(this, null)

        // Loggan med ett mjukt sken bakom
        val head = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            background = GlowDrawable(Kit.color(c, R.color.teal)); setPadding(0, dp(40), 0, dp(24))
        }
        head.addView(ImageView(c).apply {
            setImageResource(R.drawable.snc_header); adjustViewBounds = true; contentDescription = getString(R.string.company_full)
        }, LinearLayout.LayoutParams(dp(250), -2))
        head.addView(TextView(c).apply {
            text = "SIGN"; letterSpacing = 0.3f; textSize = 16f; setTextColor(Kit.color(c, R.color.gold))
            typeface = ResourcesCompat.getFont(c, R.font.cinzel_bold); gravity = Gravity.CENTER
        }, Kit.lp(c, top = 10))
        head.addView(Kit.text(c, getString(R.string.tagline).uppercase(Lang.locale(c)), 9.5f, R.color.mint, isBold = true).apply {
            letterSpacing = 0.22f; gravity = Gravity.CENTER
        })
        screen.add(head)

        // De två huvudvalen
        screen.add(action("⌗", getString(R.string.scan_document), getString(R.string.scan_document_sub), primary = true) { scan() }, 18)
        screen.add(action("📄", getString(R.string.open_document), getString(R.string.open_document_sub), primary = false) {
            openLauncher.launch(DocFormat.PICKER_MIMES)
        }, 10)

        // Sparade dokument
        val savedHead = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        savedTitle = Kit.section(c, getString(R.string.saved_documents))
        savedHead.addView(savedTitle, LinearLayout.LayoutParams(0, -2, 1f))
        savedHead.addView(Kit.text(c, getString(R.string.see_all) + "  ›", 13f, R.color.gold, isBold = true).apply {
            setPadding(dp(8), dp(22), dp(4), dp(6)); setBackgroundResource(R.drawable.bg_row)
            setOnClickListener { startActivity(Intent(c, LibraryActivity::class.java)) }
        })
        screen.add(savedHead)
        recent = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        screen.add(HorizontalScrollView(c).apply { isHorizontalScrollBarEnabled = false; addView(recent) })

        // Kontakter (Uppdateringar och Utvecklare finns i Inställningar)
        val more = Kit.card(c, 6)
        val contactsRow = Kit.row(c, "👥", getString(R.string.contacts), "") { startActivity(Intent(c, ContactsActivity::class.java)) }
        contactsSub = (contactsRow.getChildAt(1) as LinearLayout).getChildAt(1) as TextView
        more.addView(contactsRow)
        screen.add(more, 18)

        // Längst ner: "Sign 1.2" centrerat, och en kort rad när en ny version finns
        screen.add(Kit.text(c, tr(R.string.footer_line, UpdateChecker.featureVersion(UpdateChecker.currentVersionName(c))), 12.5f, R.color.dim).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(24), 0, dp(2))
        })
        updateLine = Kit.text(c, "", 12.5f, R.color.gold, isBold = true).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(4), 0, dp(8)); visibility = View.GONE
            setBackgroundResource(R.drawable.bg_row)
            setOnClickListener {
                val url = Prefs.knownLatestUrl(c)
                if (url.isNotBlank()) UpdateChecker.open(c, url) else startActivity(Intent(c, SettingsActivity::class.java))
            }
        }
        screen.add(updateLine)

        // Kugghjulet uppe till höger, med en guldprick när en ny version finns
        val gearBox = FrameLayout(c)
        val gear = ImageView(c).apply {
            setImageResource(R.drawable.ic_settings); setBackgroundResource(R.drawable.bg_round_button)
            val p = dp(10); setPadding(p, p, p, p); contentDescription = getString(R.string.settings); elevation = dp(4).toFloat()
            setOnClickListener { startActivity(Intent(c, SettingsActivity::class.java)) }
        }
        gearBox.addView(gear, FrameLayout.LayoutParams(dp(44), dp(44)))
        updateDot = View(c).apply { setBackgroundResource(R.drawable.bg_dot); visibility = View.GONE; elevation = dp(6).toFloat() }
        gearBox.addView(updateDot, FrameLayout.LayoutParams(dp(11), dp(11), Gravity.TOP or Gravity.END))
        screen.root.addView(gearBox, FrameLayout.LayoutParams(dp(46), dp(46), Gravity.TOP or Gravity.END).apply { topMargin = dp(10); rightMargin = dp(14) })
        screen.show(gearBox)

        // Signs egen startskärm, i samma stil som Trace. Visas vid varje kallstart.
        if (savedInstanceState == null) {
            screen.root.addView(
                StartScreenView(this) { screen.root.removeView(it) }.apply { elevation = 100f * d },
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            )
        }
        autoCheckForUpdate()
    }

    override fun onResume() {
        super.onResume()
        val n = runCatching { ContactBook.all(this).size }.getOrDefault(0)
        contactsSub.text = if (n == 0) getString(R.string.contacts_sub) else tr(R.string.contacts_count, n)
        showUpdate()
        renderRecent()
    }

    private fun renderRecent() {
        val d = resources.displayMetrics.density
        recent.removeAllViews()
        val docs = LibDoc.all(this)
        savedTitle.text = (getString(R.string.saved_documents) + if (docs.isNotEmpty()) " (${docs.size})" else "").uppercase(Lang.locale(this))
        if (docs.isEmpty()) {
            recent.addView(Kit.text(this, getString(R.string.no_saved_docs), 13f, R.color.dim))
            return
        }
        docs.take(6).forEachIndexed { i, doc ->
            recent.addView(DocCard.make(this, doc) {
                startActivity(Intent(this, DocPreviewActivity::class.java).putExtra(DocumentActivity.EXTRA_LIB_ID, doc.id))
            }, LinearLayout.LayoutParams((132 * d).toInt(), -2).apply { if (i > 0) marginStart = (10 * d).toInt() })
        }
    }

    private fun action(icon: String, title: String, sub: String, primary: Boolean, onClick: () -> Unit): View {
        val c = this
        val d = resources.displayMetrics.density
        val row = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(if (primary) R.drawable.bg_card_primary else R.drawable.bg_card)
            val p = (16 * d).toInt(); setPadding(p, p, p, p); minimumHeight = (84 * d).toInt()
            setOnClickListener { onClick() }
        }
        row.addView(Kit.chip(c, icon).apply { textSize = 22f }, LinearLayout.LayoutParams((52 * d).toInt(), (52 * d).toInt()))
        val t = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding((14 * d).toInt(), 0, 0, 0) }
        t.addView(Kit.text(c, title, 18f, isBold = true))
        t.addView(Kit.text(c, sub, 12.5f, R.color.muted))
        row.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(Kit.text(c, "›", 26f, R.color.gold))
        return row
    }

    /** Dokumentskannern: hittar kanterna automatiskt, flera sidor, huvudkameran. Blir en PDF. */
    private fun scan() {
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(30)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_PDF)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        GmsDocumentScanning.getClient(options).getStartScanIntent(this)
            .addOnSuccessListener { scanLauncher.launch(IntentSenderRequest.Builder(it).build()) }
            .addOnFailureListener { Toast.makeText(this, R.string.scanner_failed, Toast.LENGTH_LONG).show() }
    }

    /** Kort rad längst ner och guldprick på kugghjulet när en nyare version finns. */
    private fun showUpdate() {
        val newer = Prefs.knownLatestBuild(this) > UpdateChecker.currentBuild(this)
        updateDot.visibility = if (newer) View.VISIBLE else View.GONE
        updateLine.visibility = if (newer) View.VISIBLE else View.GONE
        if (newer) updateLine.text = tr(R.string.new_version_available, UpdateChecker.featureVersion(Prefs.knownLatestName(this)), Prefs.knownLatestName(this))
    }

    /** Söker efter ny version högst en gång per dygn, om det är påslaget i inställningarna. */
    private fun autoCheckForUpdate() {
        if (!Prefs.autoUpdate(this)) return
        if (System.currentTimeMillis() - Prefs.lastUpdateCheck(this) < 24 * 3_600_000L) return
        lifecycleScope.launch {
            val latest = try { UpdateChecker.latest(this@MainActivity) } catch (e: Exception) { return@launch }
            Prefs.setLastUpdateCheck(this@MainActivity, System.currentTimeMillis())
            Prefs.setKnownLatest(this@MainActivity, latest.build, latest.versionName, latest.apkUrl)
            showUpdate()
        }
    }
}
