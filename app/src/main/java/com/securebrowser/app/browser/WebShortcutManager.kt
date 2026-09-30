package com.securebrowser.app.browser

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import androidx.core.graphics.drawable.IconCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import com.securebrowser.app.core.url.NormalizeResult
import com.securebrowser.app.core.url.UrlNormalizer
import com.securebrowser.app.ui.FaviconLoader
import com.securebrowser.app.ui.GateActivity
import kotlin.math.abs

/**
 * نتيجة محاولة تثبيت الموقع كاختصار/تطبيق على الشاشة الرئيسية (v1.10.0).
 */
sealed class PinResult {
    data class Success(val title: String) : PinResult()
    object Unsupported : PinResult()
    data class InvalidUrl(val url: String) : PinResult()
    data class Error(val message: String) : PinResult()
}

/**
 * مدير تثبيت المواقع كتطبيقات على الشاشة الرئيسية (Home Screen Pinned Shortcuts) — v1.10.0.
 *
 * يوفر تجربة Web App كاملة:
 * 1. فحص دعم مشغل الشاشة (ShortcutManagerCompat).
 * 2. توليد أيقونة تطبيق احترافية (192×192 px) ملائمة لمشغلات Android الحديثة:
 *    - إذا وُجدت أيقونة الموقع (Favicon) تُعرض داخل لوحة مستديرة ناصعة بحدود هادئة.
 *    - إذا لم تتوفر الأيقونة، يُنشأ شارة حرفية (Monogram) ملونة بأحد ألوان Material
 *      المتناسقة والمختارة بناءً على تجزئة النطاق (Deterministic Color).
 * 3. ربط نية التشغيل عبر بوابة التطبيق [GateActivity] لضمان التحقق من رمز الوالدين
 *    وتطبيق سياسات [com.securebrowser.app.security.SecurityEngine] والقائمة البيضاء.
 */
object WebShortcutManager {

    const val ICON_SIZE = 192

    private val PALETTE = intArrayOf(
        0xFF1D4ED8.toInt(), // Blue (الهوية)
        0xFF0D9488.toInt(), // Teal
        0xFF16A34A.toInt(), // Green
        0xFFD97706.toInt(), // Amber
        0xFF7C3AED.toInt(), // Purple
        0xFFE11D48.toInt(), // Rose
        0xFF4F46E5.toInt(), // Indigo
        0xFF0284C7.toInt()  // Sky
    )

    /** هل يدعم مشغّل الشاشة الحالية تثبيت الاختصارات؟ */
    fun isSupported(context: Context): Boolean =
        ShortcutManagerCompat.isRequestPinShortcutSupported(context)

    /** هل الرابط صالح للتثبيت؟ (يجب أن يكون http أو https وليس صفحة محلية أو فارغة). */
    fun canPinUrl(rawUrl: String?): Boolean {
        if (rawUrl.isNullOrBlank()) return false
        val trimmed = rawUrl.trim()
        if (!trimmed.startsWith("http://", ignoreCase = true) &&
            !trimmed.startsWith("https://", ignoreCase = true)
        ) {
            return false
        }
        val normalized = UrlNormalizer.normalize(trimmed)
        return (normalized as? NormalizeResult.Success)?.url?.isWeb == true
    }

    /** استخراج اسم المضيف بطريقة آمنة. */
    fun extractHost(rawUrl: String): String {
        val normalized = UrlNormalizer.normalize(rawUrl)
        val parsedHost = (normalized as? NormalizeResult.Success)?.url?.host
        if (!parsedHost.isNullOrBlank()) return parsedHost
        return try {
            Uri.parse(rawUrl).host.orEmpty()
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * إنشاء أيقونة تطبيق احترافية بقياس 192×192 عالية الدقة:
     * - عند توفر Favicon: بطاقة بيضاء مستديرة بحواف ناعمة والأيقونة بالمنتصف.
     * - عند عدم توفره: بطاقة بلون مميز بحرف الموقع الأول.
     */
    fun createAppIcon(context: Context, host: String?, favicon: Bitmap?): Bitmap {
        val bitmap = Bitmap.createBitmap(ICON_SIZE, ICON_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val outerRect = RectF(6f, 6f, ICON_SIZE - 6f, ICON_SIZE - 6f)
        val cornerRadius = 40f

        if (favicon != null && !favicon.isRecycled) {
            // خلفية بيضاء نقية مع إطار خفيف
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFFFFFFF.toInt()
                style = Paint.Style.FILL
            }
            canvas.drawRoundRect(outerRect, cornerRadius, cornerRadius, bgPaint)

            val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFE2E8F0.toInt()
                style = Paint.Style.STROKE
                strokeWidth = 2.5f
            }
            canvas.drawRoundRect(outerRect, cornerRadius, cornerRadius, strokePaint)

            // رسم الأيقونة بالمنتصف بحجم مناسب (108×108)
            val iconTargetSize = 108f
            val srcW = favicon.width.toFloat().coerceAtLeast(1f)
            val srcH = favicon.height.toFloat().coerceAtLeast(1f)
            val scale = (iconTargetSize / maxOf(srcW, srcH)).coerceAtMost(iconTargetSize)
            val destW = srcW * scale
            val destH = srcH * scale
            val left = (ICON_SIZE - destW) / 2f
            val top = (ICON_SIZE - destH) / 2f
            val destRect = RectF(left, top, left + destW, top + destH)

            val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            canvas.drawBitmap(favicon, null, destRect, iconPaint)
        } else {
            // شارة حرفية ملونة
            val token = host?.removePrefix("www.")?.trim().orEmpty()
            val colorIndex = abs(token.hashCode()) % PALETTE.size
            val cardColor = PALETTE[colorIndex]

            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = cardColor
                style = Paint.Style.FILL
            }
            canvas.drawRoundRect(outerRect, cornerRadius, cornerRadius, bgPaint)

            val letter = FaviconLoader.initialFor(host)
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFFFFFFF.toInt()
                textSize = 84f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            val metrics = textPaint.fontMetrics
            val baseline = (ICON_SIZE / 2f) - ((metrics.descent + metrics.ascent) / 2f)
            canvas.drawText(letter, ICON_SIZE / 2f, baseline, textPaint)
        }

        return bitmap
    }

    /**
     * بناء نية تشغيل الاختصار:
     * يمر أولاً عبر GateActivity للتحقق من قفل الرمز الأبوي، ثم يفتح الرابط
     * مباشرة في BrowserActivity.
     */
    fun createShortcutIntent(context: Context, url: String, title: String): Intent {
        return Intent(context, GateActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse(url)
            putExtra(BrowserActivity.EXTRA_OPEN_URL, url)
            putExtra(BrowserActivity.EXTRA_SHORTCUT_TITLE, title)
            putExtra(BrowserActivity.EXTRA_FROM_SHORTCUT, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
    }

    /** معرّف مستقر لكل اختصار بناءً على الرابط المطبّع. */
    fun createShortcutId(url: String): String {
        val safeKey = url.trim().lowercase().hashCode()
        return "web_shortcut_$safeKey"
    }

    /**
     * تنفيذ طلب تثبيت الموقع على الشاشة الرئيسية.
     */
    suspend fun pinWebsite(
        context: Context,
        url: String,
        customTitle: String?,
        favicon: Bitmap? = null
    ): PinResult {
        if (!isSupported(context)) {
            return PinResult.Unsupported
        }
        if (!canPinUrl(url)) {
            return PinResult.InvalidUrl(url)
        }

        return try {
            val host = extractHost(url)
            val finalTitle = customTitle?.trim()?.ifBlank { null }
                ?: host.ifBlank { "Web App" }

            // جلب الأيقونة إن لم تُمرر
            val resolvedFavicon = favicon ?: FaviconLoader.load(context, host)
            val appIcon = createAppIcon(context, host, resolvedFavicon)

            val shortcutId = createShortcutId(url)
            val shortcutIntent = createShortcutIntent(context, url, finalTitle)

            val shortcutInfo = ShortcutInfoCompat.Builder(context, shortcutId)
                .setShortLabel(finalTitle.take(25))
                .setLongLabel(finalTitle)
                .setIcon(IconCompat.createWithBitmap(appIcon))
                .setIntent(shortcutIntent)
                .build()

            val success = ShortcutManagerCompat.requestPinShortcut(context, shortcutInfo, null)
            if (success) {
                PinResult.Success(finalTitle)
            } else {
                PinResult.Error("Launcher declined pin request")
            }
        } catch (e: Exception) {
            PinResult.Error(e.message ?: "Failed to pin shortcut")
        }
    }
}
