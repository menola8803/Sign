package se.snc.sign

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.Drawable

/**
 * Mjukt sken bakom loggan på hemskärmen. Ellipsen ryms helt i rutan och är helt genomskinlig vid kanterna,
 * så det blir aldrig någon synlig kant mot bakgrunden.
 */
class GlowDrawable(private val color: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0) return
        val rx = b.width() / 2f
        val ry = b.height() / 2f
        val r = (color and 0x00FFFFFF)
        paint.shader = RadialGradient(0f, 0f, rx,
            intArrayOf(0xE6000000.toInt() or r, 0x80000000.toInt() or r, 0x26000000 or r, r),
            floatArrayOf(0f, 0.45f, 0.75f, 1f), Shader.TileMode.CLAMP)
        canvas.save()
        canvas.translate(b.exactCenterX(), b.exactCenterY())
        canvas.scale(1f, ry / rx)
        canvas.drawCircle(0f, 0f, rx, paint)
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { paint.colorFilter = cf }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
