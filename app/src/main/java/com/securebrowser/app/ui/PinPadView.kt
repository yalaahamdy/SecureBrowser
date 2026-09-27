package com.securebrowser.app.ui

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import com.securebrowser.app.R

/**
 * لوحة إدخال رمز الوالدين — إعادة تصميم بصرية كاملة (v1.5.0).
 *
 * التطور: v1.3.0 لوحة أرقام بدل الحقل النصي → v1.4.0 وميض رقم + مسح مطوّل +
 * بصمة → **v1.5.0 هوية بصرية احترافية**:
 * - رأس أيقونة داخل دائرة متدرجة (أزرق → تيل) بأنيميشن دخول نابض.
 * - مفاتيح دائرية بلا حدود حادة، بلون السطح + ظل خفيف، بمساحة لمس 62dp
 *   ونبضة ضغط مرئية — إحساس "مفتاح حقيقي" لا زر نظام.
 * - نقاط تعبئة بأنيميشن "فرقعة" عند كل إدخالة، ومؤشر نبض للموضع التالي.
 * - رسائل الحالة داخل حبة (pill) ملونة: خطأ بلون الحاوية الحمراء، معلومة
 *   بلون السطح — بدل نص عائم بلا خلفية.
 * - أنيميشن دخول اللوحة (شفافية + انزلاق) وأنيميشن موجة نجاح على النقاط.
 * - محفوظ من v1.4.0: وميض الرقم الأخير داخل النقطة (~700ms)، ضغط مطوّل على
 *   الحذف = مسح الكل، إرسال تلقائي عند بلوغ الطول المخزن، اهتزاز لمسي
 *   تأكيدي، اهتزاز خطأ، عدّ المحاولات المتبقية عبر [setError].
 *
 * ملاحظة أمنية: هذا المكوّن عرض فقط — التحقق والقواعد وحماية القوة الغاشمة
 * تبقى حكراً على [com.securebrowser.app.parental.ParentalAuthManager].
 */
class PinPadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private var targetLength: Int? = null
    private var manualConfirm = true
    private var locked = false

    // وميض الرقم الأخير — يُعرض داخل النقطة لحظة الإدخال ثم يختفي
    private var lastDigit: String? = null
    private val digitFlashRunnable = Runnable {
        lastDigit = null
        renderDots()
    }

    var input: String = ""
        private set

    /** يُستدعى عند اكتمال الإدخال (طول معروف أو ضغط تأكيد). */
    var onComplete: ((String) -> Unit)? = null

    /** يُستدعى مع كل تغيير في الإدخال (لتحديث أسماء الخطوات مثلًا). */
    var onChanged: ((String) -> Unit)? = null

    private lateinit var iconBadge: FrameLayout
    private lateinit var titleView: TextView
    private lateinit var subtitleView: TextView
    private lateinit var dotsRow: LinearLayout
    private lateinit var statusView: TextView
    private lateinit var keypad: GridLayout
    private lateinit var confirmButton: MaterialButton
    private var cursorAnimator: ObjectAnimator? = null
    private val dotViews = mutableListOf<View>()

    private val colorPrimary by lazy { attrColor(androidx.appcompat.R.attr.colorPrimary) }
    private val colorOnBackground by lazy {
        attrColor(com.google.android.material.R.attr.colorOnBackground)
    }
    private val colorSurface by lazy {
        attrColor(com.google.android.material.R.attr.colorSurface)
    }
    private val colorError by lazy { attrColor(androidx.appcompat.R.attr.colorError) }
    private val colorErrorContainer by lazy {
        attrColor(com.google.android.material.R.attr.colorErrorContainer)
    }
    private val colorSurfaceVariant by lazy {
        attrColor(com.google.android.material.R.attr.colorSurfaceVariant)
    }
    private val colorOutline by lazy {
        attrColor(com.google.android.material.R.attr.colorOutline)
    }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        buildContent()
        buildKeypad()
        renderDots()
        playEntrance()
    }

    // ————————————————— الواجهة العامة —————————————————

    fun setTitle(text: CharSequence?) {
        titleView.text = text ?: ""
    }

    fun setSubtitle(text: CharSequence?) {
        subtitleView.text = text ?: ""
        subtitleView.isVisible = !text.isNullOrBlank()
    }

    /**
     * طول الرمز المتوقع: معروف → إرسال تلقائي بلا زر تأكيد؛
     * null → إدخال حر (4–12) مع زر تأكيد.
     */
    fun setTargetLength(length: Int?) {
        targetLength = length?.takeIf { it in MIN_LEN..MAX_LEN }
        manualConfirm = targetLength == null
        confirmButton.isVisible = manualConfirm
        confirmButton.isEnabled = input.length >= MIN_LEN
        renderDots()
    }

    fun clear(clearStatus: Boolean = true) {
        input = ""
        if (clearStatus) setNeutralStatus()
        renderDots()
        confirmButton.isEnabled = false
        onChanged?.invoke(input)
    }

    fun setError(message: String) {
        statusView.text = message
        statusView.setTextColor(colorError)
        statusView.background = pillDrawable(colorErrorContainer)
        statusView.isVisible = true
        vibrateError()
        shakeDots()
    }

    fun setInfo(message: String?) {
        statusView.text = message ?: ""
        statusView.setTextColor(colorOnBackground)
        statusView.alpha = 0.85f
        statusView.background = if (message.isNullOrBlank()) {
            null
        } else {
            pillDrawable(colorSurfaceVariant)
        }
        statusView.isVisible = !message.isNullOrBlank()
    }

    fun setErrorColor(message: String) = setError(message)

    fun markSuccess() {
        cursorAnimator?.cancel()
        // موجة نجاح: كل نقطة تنبض وتتلون بالتسلسل — إحساس إنجاز واضح
        dotViews.forEachIndexed { index, dot ->
            val bg = dot.background as? GradientDrawable ?: return@forEachIndexed
            bg.setColor(colorPrimary)
            bg.setStroke(0, Color.TRANSPARENT)
            dot.animate()
                .scaleX(1.35f).scaleY(1.35f)
                .setStartDelay(index * 40L)
                .setDuration(140L)
                .withEndAction {
                    dot.animate().scaleX(1f).scaleY(1f).setDuration(120L).start()
                }
                .start()
        }
    }

    fun setLocked(isLocked: Boolean) {
        locked = isLocked
        keypad.alpha = if (isLocked) 0.35f else 1f
        confirmButton.isEnabled = !isLocked && manualConfirm && input.length >= MIN_LEN
        for (i in 0 until keypad.childCount) {
            val child = keypad.getChildAt(i)
            child.isEnabled = !isLocked
            (child as? Button)?.isEnabled = !isLocked
        }
    }

    // ————————————————— البناء —————————————————

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun dpf(value: Float): Float =
        value * resources.displayMetrics.density

    private fun attrColor(attr: Int): Int {
        val tv = TypedValue()
        context.theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    private fun buildContent() {
        val pad = dp(20)
        setPadding(pad, dp(16), pad, 0)

        // رأس أيقونة: دائرة متدرجة بقفل أبيض — هوية بصرية واضحة
        iconBadge = FrameLayout(context).apply {
            val size = dp(64)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                colors = intArrayOf(colorPrimary, ACCENT_TEAL)
                orientation = GradientDrawable.Orientation.TL_BR
            }
            elevation = dpf(6f)
            addView(
                ImageView(context).apply {
                    setImageResource(R.drawable.ic_lock)
                    setColorFilter(Color.WHITE)
                    val iconSize = dp(28)
                    layoutParams = FrameLayout.LayoutParams(
                        iconSize, iconSize, Gravity.CENTER
                    )
                },
                FrameLayout.LayoutParams(size, size)
            )
        }

        titleView = TextView(context).apply {
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(colorOnBackground)
            gravity = Gravity.CENTER
        }
        subtitleView = TextView(context).apply {
            textSize = 14f
            setTextColor(colorOnBackground)
            alpha = 0.7f
            gravity = Gravity.CENTER
            visibility = GONE
        }
        dotsRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(22), 0, dp(6))
        }
        statusView = TextView(context).apply {
            textSize = 13f
            gravity = Gravity.CENTER
            minHeight = dp(24)
            visibility = GONE
        }
        confirmButton = MaterialButton(context).apply {
            text = context.getString(R.string.pin_pad_confirm)
            textSize = 15f
            cornerRadius = dp(18)
            insetTop = dp(4)
            insetBottom = dp(4)
            visibility = GONE
            setOnClickListener { submit() }
        }
        val confirmParams = LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) }

        addView(
            iconBadge,
            LayoutParams(dp(64), dp(64))
        )
        addView(
            titleView,
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(14) }
        )
        addView(
            subtitleView,
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(6) }
        )
        addView(dotsRow, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36)))
        addView(
            statusView,
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(4) }
        )
        addView(confirmButton, confirmParams)
    }

    private fun buildKeypad() {
        keypad = GridLayout(context).apply {
            columnCount = 3
            rowCount = 4
            useDefaultMargins = false
        }
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9")
        for (k in keys) {
            keypad.addView(keyButton(k) { onDigit(k) }, gridParams())
        }
        // الصف الأخير: فاصل — 0 — مسح (أهداف لمس واسعة)
        keypad.addView(spacerCell(), gridParams())
        keypad.addView(keyButton(localizedDigit(0)) { onDigit("0") }, gridParams())
        val deleteBtn = keyButton("⌫", R.string.pin_pad_delete_desc) { onDelete() }
        deleteBtn.setTextColor(colorOutline)
        // ضغط مطوّل = مسح الكل (تصحيح أسرع بلا 12 ضغطة)
        deleteBtn.setOnLongClickListener {
            if (locked || input.isEmpty()) return@setOnLongClickListener false
            onClearAll()
            true
        }
        keypad.addView(deleteBtn, gridParams())
        addView(
            keypad,
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(10)
            }
        )
    }

    private fun gridParams() = GridLayout.LayoutParams().apply {
        width = 0
        height = dp(62)
        columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
        rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 1)
        setMargins(dp(5), dp(5), dp(5), dp(5))
    }

    private fun spacerCell(): View = View(context)

    private fun keyButton(label: String, onClick: () -> Unit): MaterialButton =
        keyButton(label, 0, onClick)

    private fun keyButton(label: String, descRes: Int, onClick: () -> Unit): MaterialButton =
        MaterialButton(context).apply {
            text = label
            textSize = 23f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            gravity = Gravity.CENTER
            cornerRadius = dp(30)
            // مفتاح "لحمي": سطح مرتفع بلا حد حاد — ظل خفيف يعطي عمقًا
            strokeWidth = 0
            backgroundTintList = android.content.res.ColorStateList.valueOf(colorSurface)
            elevation = dpf(3f)
            setTextColor(colorOnBackground)
            insetTop = 0
            insetBottom = 0
            if (descRes != 0) contentDescription = context.getString(descRes)
            setOnClickListener {
                if (locked) return@setOnClickListener
                performHaptic()
                // نبضة ضغط مرئية — تغذية بصرية فورية عند اللمس
                animate().scaleX(0.90f).scaleY(0.90f).setDuration(60)
                    .withEndAction {
                        animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                    }
                    .start()
                onClick()
            }
        }

    // ————————————————— منطق الإدخال —————————————————

    private fun onDigit(d: String) {
        if (locked) return
        val limit = targetLength ?: MAX_LEN
        if (input.length >= limit) return
        input += d
        lastDigit = d
        // إعادة جدولة الوميض: النقطة تُظهر الرقم ~700ms ثم تصبح نقطة
        mainHandler.removeCallbacks(digitFlashRunnable)
        mainHandler.postDelayed(digitFlashRunnable, DIGIT_FLASH_MS)
        vibrate()
        renderDots()
        // فرقعة النقطة المُدخلة حديثًا — تغذية بصرية فورية
        dotViews.lastOrNull()?.let { dot ->
            dot.scaleX = 0.5f
            dot.scaleY = 0.5f
            dot.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
        }
        setNeutralStatus()
        confirmButton.isEnabled = manualConfirm && input.length >= MIN_LEN
        onChanged?.invoke(input)
        val target = targetLength
        if (target != null && input.length == target) submit()
    }

    private fun onDelete() {
        if (locked || input.isEmpty()) return
        input = input.dropLast(1)
        lastDigit = null
        mainHandler.removeCallbacks(digitFlashRunnable)
        vibrate()
        renderDots()
        setNeutralStatus()
        confirmButton.isEnabled = manualConfirm && input.length >= MIN_LEN
        onChanged?.invoke(input)
    }

    /** مسح كل الإدخال مرة واحدة (ضغط مطوّل على زر الحذف). */
    private fun onClearAll() {
        if (locked || input.isEmpty()) return
        input = ""
        lastDigit = null
        mainHandler.removeCallbacks(digitFlashRunnable)
        performHaptic()
        renderDots()
        setNeutralStatus()
        confirmButton.isEnabled = false
        onChanged?.invoke(input)
    }

    private fun submit() {
        if (locked) return
        if (input.length < MIN_LEN) {
            setError(context.getString(R.string.pin_pad_too_short))
            return
        }
        performHaptic()
        onComplete?.invoke(input)
    }

    // ————————————————— النقاط —————————————————

    private fun renderDots() {
        dotsRow.removeAllViews()
        dotViews.clear()
        cursorAnimator?.cancel()
        cursorAnimator = null

        val slots = targetLength ?: (input.length + 1).coerceIn(MIN_LEN, MAX_LEN)
        val flashIndex = if (lastDigit != null) input.length - 1 else -1
        for (i in 0 until slots) {
            val filled = i < input.length
            val isCursor = i == input.length && !locked
            // آخر نقطة مُدخلة تعرض الرقم لحظة إدخاله (وميض قصير)
            val showDigit = i == flashIndex && lastDigit != null
            val dot = TextView(context).apply {
                background = circleDrawable(filled, isCursor)
                val size = if (filled || isCursor) 16 else 14
                layoutParams = LinearLayout.LayoutParams(dp(size), dp(size)).apply {
                    marginStart = dp(7)
                    marginEnd = dp(7)
                    gravity = Gravity.CENTER_VERTICAL
                }
                if (showDigit) {
                    text = lastDigit
                    setTextColor(Color.WHITE)
                    textSize = 9f
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                }
                if (isCursor) {
                    cursorAnimator = ObjectAnimator.ofFloat(this, View.ALPHA, 0.2f, 0.7f).apply {
                        duration = 650
                        repeatCount = ObjectAnimator.INFINITE
                        repeatMode = ObjectAnimator.REVERSE
                        start()
                    }
                }
            }
            dotViews.add(dot)
            dotsRow.addView(dot)
        }
    }

    private fun circleDrawable(filled: Boolean, cursor: Boolean): GradientDrawable {
        val drawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            when {
                filled -> {
                    setColor(colorPrimary)
                    alpha = 255
                }
                cursor -> {
                    setColor(Color.TRANSPARENT)
                    setStroke(dp(2), colorPrimary)
                }
                else -> {
                    setColor(colorSurfaceVariant)
                    setStroke(0, Color.TRANSPARENT)
                }
            }
        }
        return drawable
    }

    private fun pillDrawable(color: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpf(22f)
            setColor(color)
        }

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha shl 24)

    private fun shakeDots() {
        ObjectAnimator.ofFloat(
            dotsRow,
            View.TRANSLATION_X, 0f, -18f, 18f, -12f, 12f, -6f, 6f, 0f
        ).apply { duration = 420 }.start()
    }

    private fun setNeutralStatus() {
        statusView.isVisible = false
    }

    /** أنيميشن دخول اللوحة: شفافية + انزلاق لأعلى + نبضة رأس الأيقونة. */
    private fun playEntrance() {
        alpha = 0f
        translationY = dp(18).toFloat()
        animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(240L)
            .start()
        iconBadge.scaleX = 0.6f
        iconBadge.scaleY = 0.6f
        iconBadge.animate()
            .scaleX(1f).scaleY(1f)
            .setStartDelay(90L)
            .setDuration(200L)
            .start()
    }

    // ————————————————— لمس واهتزاز —————————————————

    private fun performHaptic() {
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    private fun vibrate() {
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        val vibrator = ContextCompat.getSystemService(context, Vibrator::class.java) ?: return
        try {
            vibrator.vibrate(VibrationEffect.createOneShot(18L, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Exception) {
            // بعض الأجهزة بلا مُهيّز — الاهتزاز اللمسي أعلاه يكفي
        }
    }

    private fun vibrateError() {
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        val vibrator = ContextCompat.getSystemService(context, Vibrator::class.java) ?: return
        try {
            vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 60, 40, 60), -1))
        } catch (e: Exception) {
            // تجاهل
        }
    }

    private fun localizedDigit(d: Int): String =
        String.format(java.util.Locale.getDefault(), "%d", d)

    companion object {
        const val MIN_LEN = 4
        const val MAX_LEN = 12

        /** مدة وميض الرقم الأخير داخل النقطة قبل تحوله نقطة تعبئة. */
        private const val DIGIT_FLASH_MS = 700L

        /** لون نهاية التدرج في رأس الأيقونة (تيل مشتق من لوحة الألوان). */
        private val ACCENT_TEAL = 0xFF0E7490.toInt()

        private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    }
}
