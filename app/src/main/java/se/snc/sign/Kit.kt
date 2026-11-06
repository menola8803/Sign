package se.snc.sign

import android.content.Context
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat

/**
 * Byggstenar för skärmar som byggs i kod: samma kort, rubriker, rader och knappar som i XML-layouterna.
 * Alla skärmar får den svarta statusraden som tonar över i appens färg.
 */
class Screen(val activity: BaseActivity, title: String?) {
    private val d = activity.resources.displayMetrics.density
    fun dp(v: Int) = (v * d).toInt()

    val root = FrameLayout(activity).apply { setBackgroundColor(ContextCompat.getColor(activity, R.color.bg)) }
    val scroll = ScrollView(activity).apply {
        clipToPadding = false; isFillViewport = true
        setPadding(0, dp(8), 0, dp(24))
    }
    val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, dp(16), 0) }
    val statusBg = View(activity).apply { setBackgroundColor(0xFF000000.toInt()) }
    val scrim = View(activity).apply { setBackgroundResource(R.drawable.bg_scrim_top) }
    val back: TextView? = title?.let {
        TextView(activity).apply {
            text = "‹  $it"; textSize = 20f; setTextColor(ContextCompat.getColor(activity, R.color.ink))
            typeface = ResourcesCompat.getFont(activity, R.font.jakarta_bold)
            gravity = Gravity.CENTER_VERTICAL; minHeight = dp(48); setPadding(dp(4), 0, dp(4), 0)
            setBackgroundResource(R.drawable.bg_row)
            setOnClickListener { activity.finish() }
        }
    }

    init {
        scroll.addView(content)
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        root.addView(scrim, FrameLayout.LayoutParams(-1, dp(28)))
        root.addView(statusBg, FrameLayout.LayoutParams(-1, 0))
        back?.let { content.addView(it, LinearLayout.LayoutParams(-2, -2)) }
    }

    fun show(vararg floating: View): Screen {
        activity.setContentView(root)
        activity.fadedTopScreen(root, scroll, statusBg, scrim, *floating)
        return this
    }

    fun add(v: View, top: Int = 0): View {
        content.addView(v, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }); return v
    }
}

object Kit {
    private fun dp(c: Context, v: Int) = (v * c.resources.displayMetrics.density).toInt()
    fun color(c: Context, id: Int) = ContextCompat.getColor(c, id)
    fun bold(c: Context): Typeface? = ResourcesCompat.getFont(c, R.font.jakarta_bold)

    fun section(c: Context, text: String) = TextView(c).apply {
        this.text = text.uppercase(Lang.locale(c)); textSize = 12f; letterSpacing = 0.12f
        setTextColor(color(c, R.color.gold)); typeface = bold(c)
        setPadding(dp(c, 2), dp(c, 24), 0, dp(c, 8))
    }

    fun card(c: Context, pad: Int = 16) = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL; setBackgroundResource(R.drawable.bg_card)
        val p = dp(c, pad); setPadding(p, p, p, p)
    }

    fun text(c: Context, s: CharSequence, size: Float = 15f, colorId: Int = R.color.ink, isBold: Boolean = false) = TextView(c).apply {
        text = s; textSize = size; setTextColor(color(c, colorId)); if (isBold) typeface = bold(c)
    }

    fun divider(c: Context) = View(c).apply {
        setBackgroundColor(color(c, R.color.line))
        layoutParams = LinearLayout.LayoutParams(-1, dp(c, 1)).apply { topMargin = dp(c, 12); bottomMargin = dp(c, 12) }
    }

    fun switch(c: Context, label: String, value: Boolean, sub: String? = null, onChange: (Boolean) -> Unit): View {
        val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(c, 48) }
        val texts = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(text(c, label, 15f))
        if (sub != null) texts.addView(text(c, sub, 12f, R.color.dim))
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        val sw = Switch(c).apply { isChecked = value; setOnCheckedChangeListener { _, on -> onChange(on) } }
        row.addView(sw)
        row.setOnClickListener { sw.toggle() }
        return row
    }

    fun button(c: Context, label: String, primary: Boolean = false, danger: Boolean = false, onClick: () -> Unit) = TextView(c).apply {
        text = label; gravity = Gravity.CENTER; textSize = 14f; typeface = bold(c)
        minHeight = dp(c, 46); setPadding(dp(c, 14), 0, dp(c, 14), 0)
        setBackgroundResource(if (primary) R.drawable.bg_button_primary else R.drawable.bg_button_outline)
        setTextColor(color(c, if (primary) R.color.on_gold else if (danger) R.color.danger else R.color.ink))
        setOnClickListener { onClick() }
    }

    /** Flera knappar bredvid varandra med samma bredd. */
    fun buttonRow(c: Context, vararg buttons: View) = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL
        // Lika höga knappar på samma linje, även om texten i en av dem bryts på två rader
        isBaselineAligned = false
        buttons.forEachIndexed { i, b -> addView(b, LinearLayout.LayoutParams(0, -1, 1f).apply { if (i > 0) marginStart = dp(c, 8) }) }
    }

    /** En klickbar rad med ikon, rubrik och undertext. */
    fun row(c: Context, icon: String, title: CharSequence, sub: CharSequence? = null, chevron: Boolean = true, onClick: (() -> Unit)?): LinearLayout =
        row(c, chip(c, icon), title, sub, chevron, onClick)

    /** Samma rad med en ritad ikon (vektor) i stället för ett tecken. */
    fun row(c: Context, iconRes: Int, title: CharSequence, sub: CharSequence? = null, chevron: Boolean = true, onClick: (() -> Unit)?): LinearLayout =
        row(c, android.widget.ImageView(c).apply {
            setImageResource(iconRes); setBackgroundResource(R.drawable.bg_icon_chip)
            scaleType = android.widget.ImageView.ScaleType.CENTER
        }, title, sub, chevron, onClick)

    private fun row(c: Context, iconView: View, title: CharSequence, sub: CharSequence?, chevron: Boolean, onClick: (() -> Unit)?): LinearLayout {
        val row = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(c, 56); setPadding(dp(c, 6), dp(c, 6), dp(c, 6), dp(c, 6))
            setBackgroundResource(R.drawable.bg_row)
            if (onClick != null) setOnClickListener { onClick() }
        }
        row.addView(iconView, LinearLayout.LayoutParams(dp(c, 40), dp(c, 40)))
        val texts = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(c, 12), 0, dp(c, 6), 0) }
        texts.addView(text(c, title, 15.5f, isBold = true))
        if (sub != null) texts.addView(text(c, sub, 12.5f, R.color.dim))
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        if (chevron && onClick != null) row.addView(text(c, "›", 22f, R.color.dim))
        return row
    }

    fun chip(c: Context, icon: String) = TextView(c).apply {
        text = icon; gravity = Gravity.CENTER; textSize = 16f; typeface = bold(c)
        setTextColor(color(c, R.color.gold)); setBackgroundResource(R.drawable.bg_icon_chip)
    }

    fun input(c: Context, hint: String, value: String, type: Int = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS, onChange: ((String) -> Unit)? = null) = EditText(c).apply {
        setHint(hint); setText(value); inputType = type; textSize = 16f
        setTextColor(color(c, R.color.ink)); setHintTextColor(color(c, R.color.dim))
        setBackgroundResource(R.drawable.bg_button_outline); setPadding(dp(c, 14), 0, dp(c, 14), 0)
        minHeight = dp(c, 48); isSingleLine = true
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        if (onChange != null) addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c2: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c2: Int) {}
            override fun afterTextChanged(s: Editable?) { onChange(s?.toString().orEmpty()) }
        })
    }

    /** Två val bredvid varandra (t.ex. Fram/Bak). */
    fun segmented(c: Context, options: List<String>, selected: Int, onSelect: (Int) -> Unit): LinearLayout {
        val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false }
        val views = ArrayList<TextView>()
        fun paint(sel: Int) = views.forEachIndexed { i, v ->
            v.setBackgroundResource(if (i == sel) R.drawable.bg_button_primary else R.drawable.bg_button_outline)
            v.setTextColor(color(c, if (i == sel) R.color.on_gold else R.color.ink))
        }
        options.forEachIndexed { i, o ->
            val v = TextView(c).apply {
                text = o; gravity = Gravity.CENTER; textSize = 14f; typeface = bold(c); minHeight = dp(c, 42)
                setOnClickListener { paint(i); onSelect(i) }
            }
            views += v
            row.addView(v, LinearLayout.LayoutParams(0, -1, 1f).apply { if (i > 0) marginStart = dp(c, 8) })
        }
        paint(selected)
        return row
    }

    fun image(c: Context, wDp: Int, hDp: Int) = ImageView(c).apply {
        layoutParams = LinearLayout.LayoutParams(dp(c, wDp), dp(c, hDp))
        scaleType = ImageView.ScaleType.FIT_CENTER
    }

    fun lp(c: Context, w: Int = -1, h: Int = -2, top: Int = 0) = LinearLayout.LayoutParams(w, h).apply { topMargin = dp(c, top) }

    fun ViewGroup.addAll(vararg v: View) = v.forEach { addView(it) }
}
