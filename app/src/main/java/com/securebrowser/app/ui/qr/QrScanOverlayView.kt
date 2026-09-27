package com.securebrowser.app.ui.qr

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import kotlin.math.min

/**
 * طبقة مسح QR احترافية (v1.9.0):
 * - قناع داكن شبه شفاف مع نافذة مستديرة الزوايا (66% من أصغر البعد).
 * - أركان مميزة بلون التمييز (نمط كاميرات الاحتراف).
 * - خط ليزر متحرك رأسيًا داخل النافذة (أنيميشن مستمر).
 *
 * [windowRect] هو نفسه منطق اقتصاص فك الصورة — ما يراه المستخدم
 * داخل الإطار هو بالضبط ما يُحلَّل (أسرع وأدق).
 */
class QrScanOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MASK_COLOR }
    private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ACCENT_COLOR
        style = Paint.Style.STROKE
        strokeWidth = CORNER_STROKE_DP * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
    }
    private val laserPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ACCENT_COLOR
        style = Paint.Style.STROKE
        strokeWidth = LASER_STROKE_DP * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
        alpha = 190
    }

    private var windowRect = RectF()
    private var laserPhase = 0f
    private var animator: ValueAnimator? = null

    /** حساب نافذة المسح لأي أبعاد — يستخدمها النشاط لاقتصاص الفك. */
    fun windowRect(viewWidth: Int, viewHeight: Int): RectF {
        val size = min(viewWidth, viewHeight) * WINDOW_FRACTION
        val left = (viewWidth - size) / 2f
        val top = viewHeight * WINDOW_CENTER_Y - size / 2f
        return RectF(left, top, left + size, top + size)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) windowRect = windowRect(w, h)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = LASER_DURATION_MS
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                laserPhase = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (windowRect.isEmpty) return
        val radius = WINDOW_RADIUS_DP * resources.displayMetrics.density

        // القناع + تفريغ النافذة (طبقة offscreen لعمل CLEAR)
        val layer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        canvas.drawColor(MASK_COLOR)
        canvas.drawRoundRect(windowRect, radius, radius, clearPaint)
        canvas.restoreToCount(layer)

        // الأركان الأربعة
        val arm = CORNER_ARM_DP * resources.displayMetrics.density
        drawCorner(canvas, windowRect.left, windowRect.top, arm, 0f, 0f)
        drawCorner(canvas, windowRect.right, windowRect.top, arm, 180f, 0f)
        drawCorner(canvas, windowRect.left, windowRect.bottom, arm, 0f, 180f)
        drawCorner(canvas, windowRect.right, windowRect.bottom, arm, 180f, 180f)

        // خط الليزر المتحرك داخل النافذة (بهامش داخلي)
        val margin = radius * 0.6f
        val top = windowRect.top + margin
        val bottom = windowRect.bottom - margin
        val y = top + (bottom - top) * laserPhase
        canvas.drawLine(windowRect.left + margin, y, windowRect.right - margin, y, laserPaint)
    }

    /** قوس زاوية واحد باتجاهين (اتجاه أفق/رأس من الزاوية). */
    private fun drawCorner(canvas: Canvas, cx: Float, cy: Float, arm: Float, sx: Float, sy: Float) {
        val dx = if (sx == 0f) arm else -arm
        val dy = if (sy == 0f) arm else -arm
        canvas.drawLine(cx, cy, cx + dx, cy, cornerPaint)
        canvas.drawLine(cx, cy, cx, cy + dy, cornerPaint)
    }

    companion object {
        /** نافذة المسح = 66% من أصغر بُعد — توازن بين حجم الرمز وسرعة الفك. */
        const val WINDOW_FRACTION = 0.66f

        /** مركز النافذة الرأسي (أعلى قليلًا من المنتصف — وضعية حمل طبيعية). */
        const val WINDOW_CENTER_Y = 0.44f

        private const val MASK_COLOR = 0x9E000000.toInt()
        private const val ACCENT_COLOR = 0xFF4C8DFF.toInt()
        private const val WINDOW_RADIUS_DP = 18f
        private const val CORNER_ARM_DP = 26f
        private const val CORNER_STROKE_DP = 5f
        private const val LASER_STROKE_DP = 3f
        private const val LASER_DURATION_MS = 1600L
    }
}
