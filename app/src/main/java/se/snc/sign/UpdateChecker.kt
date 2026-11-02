package se.snc.sign

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Söker efter ny version bland releaserna i GitHub-repot, på samma sätt som Trace.
 * Varje bygge på GitHub publiceras under taggen "senaste" med raderna "Version: …" och "Build: …".
 * Vad varje version innehåller står i changelog.json i repot, på svenska och engelska.
 */
object UpdateChecker {

    const val REPO_URL = "https://github.com/menola8803/sign"
    private const val API = "https://api.github.com/repos/menola8803/sign"
    private const val CHANGELOG = "https://raw.githubusercontent.com/menola8803/sign/main/changelog.json"

    data class Release(
        val build: Long,
        val versionName: String,
        val apkUrl: String,
        val pageUrl: String,
        val publishedAt: String,
    )

    /** En funktionsversion i changelog.json, t.ex. 1.0, med text på valt språk. */
    data class Entry(
        val version: String,
        val date: String,
        val title: String,
        val summary: String,
        val changes: List<Pair<String, String>>, // typ (new/improved/fixed) och text
    )

    data class Change(val date: String, val title: String)

    fun currentBuild(ctx: Context): Long {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    }

    fun currentVersionName(ctx: Context): String =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ctx.getString(R.string.unknown_value)

    /** Funktionsversionen, t.ex. "1.0" av "1.0.12". */
    fun featureVersion(name: String): String = name.split('.').take(2).joinToString(".")

    private fun get(ctx: Context, url: String, accept: String = "application/vnd.github+json"): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Accept", accept)
            setRequestProperty("User-Agent", "Sign")
            useCaches = false
        }
        try {
            when (val code = conn.responseCode) {
                HttpURLConnection.HTTP_OK -> Unit
                HttpURLConnection.HTTP_NOT_FOUND -> throw IOException(ctx.getString(R.string.update_none_published))
                else -> throw IOException(ctx.tr(R.string.update_http, code))
            }
            return conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }

    suspend fun latest(ctx: Context): Release = withContext(Dispatchers.IO) {
        val json = JSONObject(get(ctx, "$API/releases/tags/senaste"))
        val body = json.optString("body")
        val build = Regex("""Build:\s*(\d+)""").find(body)?.groupValues?.get(1)?.toLong()
            ?: throw IOException(ctx.getString(R.string.update_bad_version))
        val name = Regex("""Version:\s*([\w.\-]+)""").find(body)?.groupValues?.get(1) ?: "build $build"
        val assets = json.optJSONArray("assets")
        var apk: String? = null
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) {
                    apk = a.optString("browser_download_url")
                    break
                }
            }
        }
        val page = json.optString("html_url", "$REPO_URL/releases")
        Release(build, name, apk ?: page, page, json.optString("published_at"))
    }

    /** Hämtar versionshistoriken (nyast först). */
    suspend fun changelog(ctx: Context): List<Entry> = withContext(Dispatchers.IO) {
        val sv = Lang.isSv(ctx)
        val root = JSONObject(get(ctx, "$CHANGELOG?t=${System.currentTimeMillis()}", "application/json"))
        val arr = root.getJSONArray("releases")
        fun JSONObject.text(key: String): String {
            val o = optJSONObject(key) ?: return optString(key)
            return if (sv) o.optString("sv", o.optString("en")) else o.optString("en", o.optString("sv"))
        }
        (0 until arr.length()).map { i ->
            val r = arr.getJSONObject(i)
            val ch = r.optJSONArray("changes") ?: JSONArray()
            Entry(
                version = r.getString("version"),
                date = r.optString("date"),
                title = r.text("title"),
                summary = r.text("summary"),
                changes = (0 until ch.length()).map { j -> ch.getJSONObject(j).let { it.optString("type") to it.text("text") } },
            )
        }.sortedWith { a, b -> compareVersions(b.version, a.version) }
    }

    /** De senaste ändringarna i repot, en rad per commit (första raden i meddelandet). */
    suspend fun recentChanges(ctx: Context, count: Int = 8): List<Change> = withContext(Dispatchers.IO) {
        val arr = JSONArray(get(ctx, "$API/commits?per_page=$count"))
        val fmt = java.time.format.DateTimeFormatter.ofPattern("d MMM", Lang.locale(ctx)).withZone(java.time.ZoneId.systemDefault())
        (0 until arr.length()).map { i ->
            val commit = arr.getJSONObject(i).getJSONObject("commit")
            val date = runCatching {
                fmt.format(java.time.Instant.parse(commit.getJSONObject("author").getString("date")))
            }.getOrDefault("")
            Change(date, commit.optString("message").lineSequence().firstOrNull().orEmpty().trim())
        }
    }

    fun compareVersions(a: String, b: String): Int {
        val pa = a.split('.').map { it.toIntOrNull() ?: 0 }
        val pb = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val d = pa.getOrElse(i) { 0 } - pb.getOrElse(i) { 0 }
            if (d != 0) return d
        }
        return 0
    }

    /** Frågar om användaren vill hämta den nya versionen. Nedladdningen sker i webbläsaren. */
    fun offer(ctx: Context, release: Release) {
        AlertDialog.Builder(ctx)
            .setTitle(R.string.update_dialog_title)
            .setMessage(ctx.tr(R.string.update_dialog_message, release.versionName, currentVersionName(ctx)))
            .setPositiveButton(ctx.tr(R.string.update_download, release.versionName)) { _, _ -> open(ctx, release.apkUrl) }
            .setNeutralButton(R.string.update_whats_new) { _, _ -> ctx.startActivity(Intent(ctx, DevActivity::class.java)) }
            .setNegativeButton(R.string.update_later, null)
            .show()
    }

    fun open(ctx: Context, url: String) {
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(ctx, ctx.tr(R.string.update_no_browser, url), Toast.LENGTH_LONG).show()
        }
    }
}
