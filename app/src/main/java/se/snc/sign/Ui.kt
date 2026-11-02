package se.snc.sign

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding

/**
 * Tonad kant i toppen, som i Trace: statusraden har appens mörka bakgrund och under den
 * tonar innehållet bort när man scrollar. Innehållet får luft för system- och navigeringsrader.
 */
fun BaseActivity.fadedTopScreen(root: View, scroll: View, statusBg: View, scrim: View, vararg floating: View) {
    val top = scroll.paddingTop
    val bottom = scroll.paddingBottom
    val margins = floating.map { (it.layoutParams as ViewGroup.MarginLayoutParams).let { lp -> Triple(lp.topMargin, lp.rightMargin, lp.leftMargin) } }
    ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        scroll.updatePadding(top = top + bars.top, bottom = bottom + bars.bottom, left = bars.left, right = bars.right)
        statusBg.updateLayoutParams<ViewGroup.LayoutParams> { height = bars.top }
        scrim.updateLayoutParams<ViewGroup.MarginLayoutParams> { topMargin = bars.top }
        floating.forEachIndexed { i, v ->
            v.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = margins[i].first + bars.top
                rightMargin = margins[i].second + bars.right
                leftMargin = margins[i].third + bars.left
            }
        }
        insets
    }
}

/** Tillbaka-knappen överst, "‹  Rubrik", som i Trace. */
fun BaseActivity.backTitle(view: TextView, title: String) {
    view.text = "‹  $title"
    view.setOnClickListener { finish() }
}
