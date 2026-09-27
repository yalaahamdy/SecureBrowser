package com.securebrowser.app.browser

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.color.MaterialColors
import com.securebrowser.app.R
import kotlin.math.abs

/**
 * SwipeRefreshLayout بمنطق "هل يمكن التمرير لأعلى؟" مُفوَّض لـ WebView الحي (v1.6.0).
 *
 * السبب: الابن المباشر للوح هو FrameLayout وسيط (webViewContainer) لا يعرف
 * حالة تمرير الصفحة — بدونه كان السحب من أي موضع في الصفحة سيُطلق التحديث
 * حتى أثناء القراءة في منتصف/أسفل الصفحة. المفوِّض يجعل السحب للتحديث يعمل
 * **فقط في أعلى الصفحة** — السلوك القياسي نفسه الذي تتصرف به المتصفحات.
 */
class BrowserSwipeRefresh @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SwipeRefreshLayout(context, attrs) {

    /** يُرجع true إذا كان المحتوى يمكن تمريره نحو الأعلى (لست في قمة الصفحة). */
    var canScrollUpDelegate: (() -> Boolean)? = null

    override fun canChildScrollUp(): Boolean =
        canScrollUpDelegate?.invoke() ?: super.canChildScrollUp()
}

/**
 * إيماءات التنقل من الحواف — البديل الاحترافي للشريط السفلي المحذوف (v1.6.0):
 *
 * - السحب الأفقي من الحافة اليسرى → **رجوع**، ومن اليمنى → **تقدم**
 *   (v1.8.0: **ثابتان بلا انعكاس RTL** — نفس سلوك كروم/سامسونج/إيدج:
 *   كان الانعكاس يجعل سحبة اليسار = تقدم في العربية فلا يحدث شيء مع سجل
 *   تقدم فارغ، والمستخدم معتاد أن الحافة اليسرى رجوع في كل متصفح والنظام).
 * - **مراقبة بلا استهلاك:** يُستدعى من dispatchTouchEvent للنشاط ويعيد دائمًا
 *   التحكم للسلسلة الأصلية — WebView يبقى تفاعليًا بالكامل (روابط، تمرير،
 *   تحديد نص، تكبير) لأننا لا نلتقط أي حدث أبدًا.
 *   v1.8.0: حجز شرائط الحواف من إيماءة النظام يتم في BrowserActivity
 *   (systemGestureExclusionRects) — بدونه كان النظام يستهلك السحب الجانبي
 *   قبل وصوله للتطبيق على Android 10+ بالتنقل بالإيماءات.
 * - **قفل اتجاه:** بعد تجاوز ميل اللمس يُقفل الاتجاه (أفقي أم رأسي) — الميل
 *   الرأسي يلغي الإيماءة فورًا كي لا تتعارض مع تمرير الصفحة، والقفل يمنع
 *   الانقطاع الأوسط أثناء السحب المائل قليلًا.
 * - **مؤشر حي يتبع الإصبع:** دائرة بسهم يظهر بشفافية وحجم متدرجين مع نسبة
 *   التقدم، يتبع أفقيًا ورأسيًا ضمن حدود الحاوية، وينطلق النقل عند تجاوز
 *   مسافة الزناد فقط (64dp منذ v1.8.0 بدل 84 — زناد أسرع وأسهل) — وإلا عاد
 *   المؤشر مخفيًا بأنيميشن (سحب مُلغى)، مع نبضة لمس حسية عند الإطلاق.
 */
class EdgeNavGestureDetector(
    private val host: android.view.ViewGroup,
    private val onBack: () -> Unit,
    private val onForward: () -> Unit
) {

    private val touchSlop = ViewConfiguration.get(host.context).scaledTouchSlop
    private val edgeWidth = host.context.dp(EDGE_WIDTH_DP)
    private val triggerDistance = host.context.dp(TRIGGER_DISTANCE_DP)
    private val indicatorSize = host.context.dp(INDICATOR_SIZE_DP)
    private val density = host.context.resources.displayMetrics.density

    private var pointerId = -1
    private var side: Int = SIDE_NONE
    private var directionLocked = false
    private var fired = false
    private var downX = 0f
    private var downY = 0f
    private var lastProgress = 0f

    private var indicator: FrameLayout? = null

    /**
     * نقطة الدخول من dispatchTouchEvent — **يراقب فقط ولا يستهلك**، والقيمة
     * المُعادة دائمًا false أي أن النشاط يمرر الحدث كما هو للسلسلة الأصلية.
     */
    fun observe(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> onDown(ev)
            MotionEvent.ACTION_MOVE -> onMove(ev)
            MotionEvent.ACTION_UP -> onUp(ev)
            MotionEvent.ACTION_CANCEL -> reset()
        }
    }

    private fun onDown(ev: MotionEvent) {
        pointerId = ev.getPointerId(0)
        fired = false
        directionLocked = false
        side = SIDE_NONE
        downX = ev.x
        downY = ev.y
        lastProgress = 0f

        // v1.8.0: ثابت بلا انعكاس RTL — الحافة اليسرى رجوع واليمنى تقدم دائمًا
        // (سلوك كروم/سامسونج/إيدج الموحد؛ الانعكاس كان يجعل سحبة اليسار
        // تقدمًا مع سجل تقدم فارغ فتبدو الإيماءة «معطلة» تمامًا)
        val fromLeft = ev.x <= edgeWidth
        val fromRight = ev.x >= host.width - edgeWidth
        side = when {
            fromLeft -> SIDE_BACK
            fromRight -> SIDE_FORWARD
            else -> SIDE_NONE
        }
        if (side != SIDE_NONE) showIndicator(downY, 0f)
    }

    private fun onMove(ev: MotionEvent) {
        if (side == SIDE_NONE || fired) return
        val index = ev.findPointerIndex(pointerId)
        if (index < 0) return
        val x = ev.getX(index)
        val y = ev.getY(index)
        val dx = x - downX
        val dy = y - downY

        if (!directionLocked) {
            if (abs(dx) <= touchSlop && abs(dy) <= touchSlop) {
                updateIndicator(y, 0f)
                return
            }
            if (abs(dy) > abs(dx)) {
                // الحركة الرأسيّة غلبت — هذا تمرير صفحة، الإيماءة تُلغى فورًا
                reset()
                return
            }
            directionLocked = true
        }

        // المسافة الداخلية الموجبة: لليمين عند الرجوع (حافة يسرى)، لليسار عند التقدم
        val traveled = if (side == SIDE_BACK) dx else -dx
        if (traveled < -touchSlop * 2) {
            // سحب عكسي خارج الحافة — إلغاء صريح
            reset()
            return
        }
        val progress = (traveled / triggerDistance).coerceIn(0f, 1f)
        lastProgress = progress
        updateIndicator(y, progress)
    }

    private fun onUp(ev: MotionEvent) {
        if (side == SIDE_NONE || fired) {
            reset()
            return
        }
        val index = ev.findPointerIndex(pointerId)
        val y = if (index >= 0) ev.getY(index) else downY
        val success = directionLocked && lastProgress >= 1f
        val action = if (side == SIDE_BACK) onBack else onForward
        if (success) {
            reset()
            fired = true
            // نبضة لمس حسية عند الإطلاق (v1.8.0) — إيجابية بلمس الواجهة
            host.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            animateLaunch()
            action()
        } else {
            // إبقاء المؤشر مرئيًا لأنيميشن الخفوت — الحالة المنطقية فقط تُصفّر
            side = SIDE_NONE
            directionLocked = false
            pointerId = -1
            lastProgress = 0f
            animateDismiss(y)
        }
    }

    /** انطلاقة نجاح: نبضة تكبير ثم خفوت سريع. */
    private fun animateLaunch() {
        indicator?.animate()
            ?.alpha(0f)
            ?.scaleX(1.25f)
            ?.scaleY(1.25f)
            ?.setDuration(150L)
            ?.withEndAction {
                indicator?.let {
                    it.visibility = View.INVISIBLE
                    it.scaleX = 1f
                    it.scaleY = 1f
                }
            }
            ?.start()
    }

    // ————————————————— المؤشر الدائري الحي —————————————————

    private fun ensureIndicator(): FrameLayout {
        indicator?.let { return it }
        val ctx = host.context
        val circle = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(
                MaterialColors.getColor(
                    host,
                    com.google.android.material.R.attr.colorSurface,
                    ContextCompat.getColor(ctx, android.R.color.white)
                )
            )
            setStroke(ctx.dp(1), 0x22000000)
        }
        val arrow = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_back)
            setColorFilter(
                MaterialColors.getColor(
                    host,
                    androidx.appcompat.R.attr.colorPrimary,
                    ContextCompat.getColor(ctx, android.R.color.black)
                )
            )
        }
        val box = FrameLayout(ctx).apply {
            background = circle
            elevation = ctx.dp(6).toFloat()
            addView(
                arrow,
                FrameLayout.LayoutParams(indicatorSize / 2, indicatorSize / 2, Gravity.CENTER)
            )
            visibility = View.INVISIBLE
        }
        host.addView(
            box,
            FrameLayout.LayoutParams(indicatorSize, indicatorSize, Gravity.TOP or Gravity.START)
        )
        // فوق الطبقات (بداية/حظر/خطأ) لكن تحت حاوية ملء الشاشة
        (box.layoutParams as FrameLayout.LayoutParams).topMargin = 0
        indicator = box
        // السهم يشير للاتجاه الصحيح حسب نوع الإيماءة عند كل ظهور
        box.tag = arrow
        return box
    }

    private fun showIndicator(y: Float, progress: Float) {
        val box = ensureIndicator()
        (box.tag as? ImageView)?.let { arrow ->
            arrow.setImageResource(
                if (side == SIDE_BACK) R.drawable.ic_back else R.drawable.ic_forward
            )
        }
        box.visibility = View.VISIBLE
        box.animate().cancel()
        updateIndicator(y, progress)
    }

    private fun updateIndicator(y: Float, progress: Float) {
        val box = indicator ?: return
        // side محسوم في onDown (رجوع=يسار / تقدم=يمين دائمًا منذ v1.8.0) — تموضع فعلي فقط
        val margin = density * 6
        val maxFollowX = triggerDistance + indicatorSize / 2
        val followX = (lastProgress * maxFollowX).coerceAtMost(host.width / 2f)
        val tx = when (side) {
            SIDE_BACK -> margin + followX
            else -> host.width - indicatorSize - margin - followX
        }
        val ty = (y - indicatorSize / 2f).coerceIn(0f, (host.height - indicatorSize).toFloat())
        box.translationX = tx
        box.translationY = ty
        box.alpha = (0.2f + 0.8f * progress)
        val scale = 0.7f + 0.3f * progress
        box.scaleX = scale
        box.scaleY = scale
    }

    private fun animateDismiss(y: Float) {
        val box = indicator ?: return
        box.animate()
            .alpha(0f)
            .scaleX(0.6f)
            .scaleY(0.6f)
            .setDuration(160L)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                box.visibility = View.INVISIBLE
                box.scaleX = 1f
                box.scaleY = 1f
            }
            .start()
    }

    /** إخفاء فوري بلا أنيميشن (إلغاء/بدء جديد). */
    private fun reset() {
        side = SIDE_NONE
        directionLocked = false
        lastProgress = 0f
        pointerId = -1
        indicator?.let {
            it.animate().cancel()
            it.visibility = View.INVISIBLE
            it.alpha = 0f
        }
    }

    private fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        /** v1.8.0: 34dp بدل 26 — منطقة التقاط أوسع تطابقًا مع شريط استبعاد النظام. */
        private const val EDGE_WIDTH_DP = 34

        /** v1.8.0: 64dp بدل 84 — زناد أسرع (نصف عرض الشاشة تقريبًا في اليد الواحدة). */
        private const val TRIGGER_DISTANCE_DP = 64

        private const val INDICATOR_SIZE_DP = 44

        private const val SIDE_NONE = 0
        private const val SIDE_BACK = 1
        private const val SIDE_FORWARD = 2
    }
}
