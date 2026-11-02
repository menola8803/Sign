package se.snc.sign

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.text.buildSpannedString
import androidx.core.text.color
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/** Hemskärmen: öppna ett dokument, inställningar och utveckling. Visar startskärmen vid kallstart. */
class MainActivity : BaseActivity() {

    private val openLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) startActivity(Intent(this, DocumentActivity::class.java).setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        fadedTopScreen(findViewById(R.id.root), findViewById(R.id.scroll), findViewById(R.id.statusBg), findViewById(R.id.topScrim), findViewById(R.id.btnSettings), findViewById(R.id.sncLogo))

        findViewById<TextView>(R.id.headline).text = buildSpannedString {
            append(getString(R.string.home_headline_1) + " ")
            color(ContextCompat.getColor(this@MainActivity, R.color.mint)) { append(getString(R.string.home_headline_2)) }
        }
        findViewById<View>(R.id.cardOpen).setOnClickListener {
            openLauncher.launch(arrayOf("application/pdf", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
        }
        findViewById<View>(R.id.rowSettings).setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        findViewById<View>(R.id.rowContacts).setOnClickListener { startActivity(Intent(this, ContactsActivity::class.java)) }
        findViewById<View>(R.id.rowDev).setOnClickListener { startActivity(Intent(this, DevActivity::class.java)) }
        findViewById<View>(R.id.btnSettings).setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        findViewById<TextView>(R.id.homeVersion).text = tr(R.string.version_line, UpdateChecker.currentVersionName(this))

        // Signs egen startskärm, i samma stil som Trace. Visas vid varje kallstart.
        if (savedInstanceState == null) {
            val root = findViewById<FrameLayout>(R.id.root)
            root.addView(
                StartScreenView(this) { root.removeView(it) }.apply { elevation = 100f * resources.displayMetrics.density },
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            )
        }
        autoCheckForUpdate()
    }

    override fun onResume() {
        super.onResume()
        val n = Contacts.all(this).size
        findViewById<TextView>(R.id.contactsLine).text = if (n == 0) getString(R.string.contacts_sub) else tr(R.string.contacts_count, n)
        val photo = Store.hasPhoto(this) && Prefs.includePhoto(this)
        val name = Prefs.name(this)
        findViewById<TextView>(R.id.profileLine).text = when {
            name.isNotBlank() && photo -> tr(R.string.profile_name_photo, name)
            name.isNotBlank() -> name
            photo -> getString(R.string.profile_photo_only)
            else -> getString(R.string.profile_empty)
        }
    }

    /** Söker efter ny version högst en gång per dygn, om det är påslaget i inställningarna. */
    private fun autoCheckForUpdate() {
        if (!Prefs.autoUpdate(this)) return
        if (System.currentTimeMillis() - Prefs.lastUpdateCheck(this) < 24 * 3_600_000L) return
        lifecycleScope.launch {
            val latest = try { UpdateChecker.latest(this@MainActivity) } catch (e: Exception) { return@launch }
            Prefs.setLastUpdateCheck(this@MainActivity, System.currentTimeMillis())
            if (latest.build > UpdateChecker.currentBuild(this@MainActivity)) {
                findViewById<View>(R.id.updateDot).visibility = View.VISIBLE
                UpdateChecker.offer(this@MainActivity, latest)
            }
        }
    }
}
