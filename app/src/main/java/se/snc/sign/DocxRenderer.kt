package se.snc.sign

import android.annotation.SuppressLint
import android.app.Activity
import android.util.Base64
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Word-dokument (.docx) ritas upp med docx-preview i en osynlig WebView och fotograferas sida för sida.
 * Allt sker lokalt i telefonen; inga filer skickas någonstans.
 */
class DocxRenderer(private val activity: Activity) {

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    /**
     * mode: "docx" (docx-preview), "odt" (läses i JavaScript) eller "html" (färdig HTML, t.ex. från .doc).
     * layoutJson: sidstorlek och marginaler i CSS-pixlar för "html".
     */
    suspend fun render(
        docx: File, outDir: File, onProgress: (Int, Int) -> Unit,
        mode: String = "docx", html: String? = null, layoutJson: String? = null,
    ): ImagePages =
        suspendCancellableCoroutine { cont ->
            outDir.deleteRecursively(); outDir.mkdirs()
            val files = ArrayList<File>()
            val sizes = ArrayList<Pair<Float, Float>>()
            val anchors = ArrayList<Int>()
            val host = activity.findViewById<ViewGroup>(android.R.id.content)
            val web = WebView(activity)
            var finished = false

            fun finish(err: Throwable?) {
                if (finished) return
                finished = true
                activity.runOnUiThread {
                    host.removeView(web)
                    web.destroy()
                    if (err != null) cont.resumeWithException(err)
                    else cont.resume(ImagePages(files, sizes, anchors))
                }
            }

            web.settings.javaScriptEnabled = true
            web.settings.allowFileAccess = true
            web.alpha = 0f
            web.addJavascriptInterface(object {
                @JavascriptInterface
                fun docx(): String = Base64.encodeToString(docx.readBytes(), Base64.NO_WRAP)

                @JavascriptInterface
                fun mode(): String = mode

                @JavascriptInterface
                fun html(): String = html ?: ""

                @JavascriptInterface
                fun layout(): String = layoutJson ?: """{"w":793.7,"h":1122.5,"mt":75.6,"mb":75.6,"ml":75.6,"mr":75.6}"""

                @JavascriptInterface
                fun page(index: Int, total: Int, widthPx: Float, heightPx: Float, anchor: Int, jpegBase64: String) {
                    val f = File(outDir, "page-%03d.jpg".format(index))
                    f.writeBytes(Base64.decode(jpegBase64, Base64.DEFAULT))
                    synchronized(files) {
                        files.add(f)
                        sizes.add(widthPx * 0.75f to heightPx * 0.75f) // px → pt
                        anchors.add(anchor)
                    }
                    activity.runOnUiThread { onProgress(index + 1, total) }
                }

                @JavascriptInterface
                fun done() = finish(null)

                @JavascriptInterface
                fun fail(message: String) = finish(IllegalStateException(message))
            }, "Android")
            web.webViewClient = WebViewClient()
            host.addView(web, 0, FrameLayout.LayoutParams(1200, 1600))
            web.loadUrl(if (mode == "docx") "file:///android_asset/docx/render.html" else "file:///android_asset/docx/render-html.html")
            cont.invokeOnCancellation { finish(null) }
        }
}
