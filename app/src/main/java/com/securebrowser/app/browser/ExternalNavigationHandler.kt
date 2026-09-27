package com.securebrowser.app.browser

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.securebrowser.app.R
import com.securebrowser.app.data.repository.SettingsRepository
import com.securebrowser.app.di.ServiceLocator
import com.securebrowser.app.policy.ExternalAppPolicy
import com.securebrowser.app.policy.ExternalUriBuilder
import kotlinx.coroutines.launch

/**
 * معالج التنقل الخارجي — سياسة التطبيقات الخارجية الكاملة (مرحلة 2 §19-§20 + v1.2.0 + v1.4.0).
 *
 * قبل فتح أي تطبيق خارجي تُطبَّق البوابات بالترتيب:
 * 1. إعداد الوالدين "external_apps" (افتراضي: مسموح) — من لوحة التحكم فقط.
 * 2. إعداد "video_players" لمشغلات الوسائط (intent وسائطي/مخطط مشغل معروف).
 * 3. فحص المخطط/الرابط — تم بالفعل عبر SecurityEngine قبل الوصول هنا.
 * 4. تأكيد المستخدم الصريح ("فتح في Telegram؟") عندما يكون مفعّلًا في الإعدادات.
 * 5. الإطلاق المحصّن — فشل = حوار واضح مع نسخ الرابط، بلا انهيار ولا توقف صامت.
 *
 * **إصلاح v1.4.0 الجوهري — إطلاق بلا فحص حلٍّ مسبق للنيّات غير المثبتة بحزمة:**
 * كان v1.3.0 يفحص resolveActivity/queryIntentActivities قبل الإطلاق — على
 * Android 11+ يخضع الفحص لقيود <queries> فتعيد empty لأن التطبيق غير مرئي
 * حتى لو كان مثبتًا وناجح startActivity → كنا نعرض "لا يوجد تطبيق" ولا نحاول
 * الإطلاق أصلًا. الآن: النيّات بلا تثبيت حزمة (مخططات عامة/مشغلات/intent)
 * تُطلَق **مباشرة** (startActivity غير مقيّد برؤية الحزم) والفشل يُلتقط
 * ActivityNotFoundException → حوار واضح. الفحص المسبق يبقى للنيّات المثبتة
 * بحزمة (tg/whatsapp/youtube) حيث منطق إعادة المحاولة بلا حزمة يحتاج النتيجة.
 *
 * **إصلاح v1.4.0 ثانٍ — مخططات التطبيقات العامة:** أي مخطط غير معروف
 * (viber/zoommtg/playit/...) يُبنى ACTION_VIEW عام بلا تثبيت حزمة — أي تطبيق
 * سجّل المخطط يفتحه، بدل إسقاطها بـ "لا يوجد تطبيق".
 *
 * الروابط التي يمنعها Whitelist: إن كانت لنطاق تطبيق معروف (t.me / wa.me / youtube /
 * maps) تُعرض بدائل "فتح في التطبيق" أو الاكتفاء بشاشة الحظر — لا تجاوز للقائمة مطلقًا.
 */
class ExternalNavigationHandler(
    private val activity: AppCompatActivity,
    /** فتح رابط احتياطي (S.browser_fallback_url) داخل المتصفح — يمر بالأمان كأي تنقل. */
    private val fallbackNavigator: ((String) -> Unit)? = null,
    /** هل الرابط الاحتياطي مسموح بالقائمة؟ (لعرض زره في حوار عدم وجود تطبيق) */
    private val fallbackValidator: ((String) -> Boolean)? = null
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val settings: SettingsRepository get() = ServiceLocator.settingsRepository

    /** مخططات اتصال/تطبيقات مباشرة (tel/sms/mailto/geo/tg/whatsapp/vnd.youtube/intent/vlc...). */
    fun handle(scheme: String, target: String) {
        if (!settings.externalApps) {
            notifyBlockedExternal()
            return
        }
        // بوابة مشغلات الوسائط — منفصلة عن بوابة التطبيقات العامة (قرار والدين مستقل)
        // ملاحظة: target لمخطط intent هو نص الـ URI الكامل (intent://...) كما يرجعه SecurityEngine.
        val isMedia = ExternalAppPolicy.isMediaScheme(scheme) ||
            (scheme == "intent" && ExternalAppPolicy.isMediaIntent(target))
        if (isMedia && !settings.videoPlayers) {
            notifyVideoPlayersDisabled()
            return
        }

        val intent: Intent = when {
            scheme == "tel" -> Intent(Intent.ACTION_DIAL, Uri.parse(target))
            scheme == "mailto" || scheme == "sms" || scheme == "smsto" ->
                Intent(Intent.ACTION_SENDTO, Uri.parse(target))
            scheme == "geo" -> Intent(Intent.ACTION_VIEW, Uri.parse(sanitizeGeo(target)))
            scheme == "market" -> Intent(Intent.ACTION_VIEW, Uri.parse(target))
            ExternalAppPolicy.isMediaScheme(scheme) ->
                // مشغل وسائط: Action VIEW عام بدون تثبيت حزمة — أي مشغل سجّل المخطط يفتحها
                Intent(Intent.ACTION_VIEW, Uri.parse("$scheme:$target"))
            scheme == "tg" || scheme == "whatsapp" ||
                scheme == "vnd.youtube" || scheme == "youtube" -> buildAppIntent(scheme, target)
            scheme == "intent" -> parseIntentSafely(target)
            else ->
                // v1.4.0: مخطط تطبيق عام غير معروف — إعادة بناء URI الأصلي كما وصل
                // (target هو opaquePart: لـ viber://chat يكون "//chat") وإطلاقه بلا فحص حلّ
                Intent(Intent.ACTION_VIEW, Uri.parse(ExternalUriBuilder.buildUriText(scheme, target)))
        } ?: run {
            notifyNoApp(scheme, null, fallbackUrlFor(target, scheme))
            return
        }

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        // كشف التطبيق المثبت **للنيّات المثبتة بحزمة فقط** (v1.4.0 — انظر KDoc أعلاه):
        // المخططات العامة/المشغلات/intent تُطلق مباشرة — startActivity غير مقيّد
        // برؤية الحزم، وفشله (لا معالج) يُلتقط في launchRobust ويُعرض كحوار واضح.
        val pinned = intent.`package` != null
        if (pinned) {
            val resolved = resolveWithFallback(intent, scheme)
            if (!resolved) {
                notifyNoApp(scheme, intent.data, fallbackUrlFor(target, scheme))
                return
            }
        }

        val appLabel = if (pinned) resolveAppLabel(intent)
        else resolveAppLabelOrNull(intent) ?: activity.getString(R.string.external_app_generic)
        maybeConfirm(appLabel) {
            launchRobust(intent, scheme, appLabel, fallbackUrlFor(target, scheme))
        }
    }

    /**
     * v1.8.0 — رابط احتياطي (S.browser_fallback_url) لـ intent:// فقط:
     * المتصفحات المعروفة تعرض هذا الرابط عندما لا يجد التطبيق المستهدف —
     * نستخرجه لعرض زر «فتح الرابط في المتصفح» داخل حوار عدم وجود التطبيق،
     * ويبقى فتحه رهينًا بالقائمة البيضاء عبر fallbackValidator.
     */
    private fun fallbackUrlFor(target: String, scheme: String): String? {
        if (scheme != "intent") return null
        return ExternalAppPolicy.intentFallbackUrl(target)
    }

    /**
     * إطلاق محصّن لكل السيناريوهات:
     * 1) إطلاق مباشر؛ 2) عند فشل (تطبيق اختفى/رؤية/حزمة خاطئة) إعادة إطلاق بلا حزمة؛
     * 3) عند الفشل الكامل حوار فشل واضح مع نسخ الرابط — لا صمت ولا انهيار.
     */
    private fun launchRobust(intent: Intent, scheme: String, appLabel: String, fallbackUrl: String? = null) {
        toast(activity.getString(R.string.external_opening, appLabel))
        try {
            activity.startActivity(intent)
        } catch (first: Exception) {
            // المحاولة الثانية: بلا تثبيت حزمة (أي عميل سجّل المخطط/المعالج)
            val generic = Intent(intent.action, intent.data).apply {
                `package` = null
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val secondOk = try {
                activity.startActivity(generic)
                true
            } catch (second: Exception) {
                false
            }
            if (!secondOk) {
                ServiceLocator.applicationScope.launch {
                    ServiceLocator.blockedActivityRepository.record(
                        url = intent.data?.toString()?.take(2048),
                        host = null,
                        reason = "EXTERNAL_LAUNCH_FAILED:${first.javaClass.simpleName}",
                        navigationType = "EXTERNAL"
                    )
                }
                notifyNoApp(scheme, intent.data, fallbackUrl)
            }
        }
    }

    /**
     * رابط ويب يحظرته القائمة البيضاء — إن كان لنطاق تطبيق معروف ومثبت
     * نعرض "فتح في التطبيق"، وإلا نُظهر شاشة الحظر كما هي.
     */
    fun handleBlockedWebUrl(rawUrl: String, onShowBlock: () -> Unit) {
        val suggestion = ExternalAppPolicy.suggestForWebUrl(rawUrl)
        if (suggestion == null || !settings.externalApps) {
            onShowBlock()
            return
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(suggestion.webUrl))
        intent.setPackage(packageFor(suggestion.kind))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        var resolved = try {
            resolveVisible(intent)
        } catch (e: Exception) {
            false
        }
        if (!resolved) {
            // الحزمة الأساسية غير موجودة/غير مرئية → محاولة بلا تثبيت حزمة:
            // أي عميل سجّل نية VIEW لهذا الرابط (Telegram X مثلًا) يظهر.
            val generic = Intent(Intent.ACTION_VIEW, Uri.parse(suggestion.webUrl))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            resolved = try {
                resolveVisible(generic)
            } catch (e: Exception) {
                false
            }
            if (resolved) {
                intent.`package` = null
            }
        }
        if (!resolved) {
            // لا يوجد تطبيق → شاشة الحظر كما هي
            toast(activity.getString(R.string.toast_app_not_installed, suggestion.appLabel))
            onShowBlock()
            return
        }
        maybeConfirm(
            activity.getString(R.string.external_open_in_app, suggestion.appLabel)
        ) {
            launchRobust(intent, suggestion.kind.name, suggestion.appLabel)
        }
    }

    // ————————————————— المساعدات —————————————————

    /**
     * محاولة حل النية مع الحزم الظاهرة:
     * - مخططات التطبيقات المثبتة بالحزمة (tg → org.telegram.messenger): إن لم يُحلَّ
     *   نعيد المحاولة على نسخة **بلا setPackage** — أي عميل سجّل المخطط (Telegram X
     *   مثلًا) يظهر. هذا يمنع "لا يوجد تطبيق" الخاطئة وهو أحد أسباب تعذر الاتصال بتلجرام.
     * - tel/sendto بلا فحص (مقبولون نظاميًا دائمًا)، وintent: نثق بحل النظام مع
     *   إعادة الإطلاق المحصّنة عند التنفيذ.
     * - v1.3.0: فحص مزدوج resolveActivity ثم queryIntentActivities — بعض الأجهزة
     *   تعيد null من الأولى مع تعدد المعالجات رغم وجود معالجات فعلية.
     */
    private fun resolveWithFallback(intent: Intent, scheme: String): Boolean = try {
        val pm = activity.packageManager
        if (scheme == "intent" || intent.action == Intent.ACTION_DIAL ||
            intent.action == Intent.ACTION_SENDTO
        ) {
            true
        } else if (resolveVisible(intent)) {
            true
        } else if (intent.`package` != null) {
            val generic = Intent(intent.action, intent.data)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            resolveVisible(generic).also { resolved ->
                if (resolved) intent.`package` = null
            }
        } else {
            false
        }
    } catch (e: Exception) {
        false
    }

    private fun resolveVisible(intent: Intent): Boolean = try {
        val pm = activity.packageManager
        pm.resolveActivity(intent, 0) != null ||
            pm.queryIntentActivities(intent, 0).isNotEmpty()
    } catch (e: Exception) {
        false
    }

    /**
     * بناء نية تطبيق معروف — مع توحيد صيغة tg:
     * SecurityEngine يعيد target كـ opaquePart، فلـ tg://resolve?domain=x تكون
     * "//resolve?domain=x" (صحيحة)، بينما tg:resolve?domain=x تكون
     * "resolve?domain=x" — دمجها كـ "tg:resolve..." ينتج URI معتمًا (opaque)
     * لا يطابق intent-filter التي تشترط host. التوحيد إلى "tg://..." دائمًا
     * يضمن المطابقة مع كل عملاء تلجرام.
     */
    private fun buildAppIntent(scheme: String, target: String): Intent? {
        val pkg = when (scheme) {
            "tg" -> "org.telegram.messenger"
            "whatsapp" -> "com.whatsapp"
            "vnd.youtube", "youtube" -> "com.google.android.youtube"
            else -> return null
        }
        // توحيد الصيغة (tg:opaque → tg://host) — المواصفة في ExternalUriBuilder
        return Intent(Intent.ACTION_VIEW, Uri.parse(ExternalUriBuilder.buildUriText(scheme, target)))
            .apply { setPackage(pkg) }
    }

    private fun parseIntentSafely(target: String): Intent? = try {
        Intent.parseUri(target, Intent.URI_INTENT_SCHEME)?.apply {
            // تحصين v1.8.0 — منع intent redirection (أفضل الممارسات الموثقة):
            // لا نسمح للصفحة بتحديد مكوّن صريح (component=) أو محدّد (selector=)
            // — الوجهة تُحسم بالحزمة + الفعل + البيانات فقط، وأي مكوّن غير
            // مُصدَّر في تطبيق آخر كان سيُرفض من النظام أصلًا، والتجريد يمنع
            // محاولات استهداف مكوّنات مميّزة داخل التطبيقات.
            component = null
            selector = null
        }
    } catch (e: Exception) {
        null
    }

    private fun sanitizeGeo(target: String): String =
        if (target.startsWith("geo:")) target else "geo:0,0?q=${Uri.encode(target)}"

    private fun packageFor(kind: ExternalAppPolicy.AppKind): String = when (kind) {
        ExternalAppPolicy.AppKind.TELEGRAM -> "org.telegram.messenger"
        ExternalAppPolicy.AppKind.WHATSAPP -> "com.whatsapp"
        ExternalAppPolicy.AppKind.YOUTUBE -> "com.google.android.youtube"
        ExternalAppPolicy.AppKind.MAPS -> "com.google.android.apps.maps"
        else -> ""
    }

    private fun resolveAppLabel(intent: Intent): String =
        resolveAppLabelOrNull(intent) ?: activity.getString(R.string.external_app_generic)

    /** اسم التطبيق إن وُجد ضمن الظاهرة — null عند غياب الرؤية (لا يمنع الإطلاق v1.4.0). */
    private fun resolveAppLabelOrNull(intent: Intent): String? = try {
        val pm = activity.packageManager
        pm.resolveActivity(intent, 0)?.loadLabel(pm)?.toString()
    } catch (e: Exception) {
        null
    }

    /** بوابة التأكيد — الإعداد "external_confirm" يُدار من لوحة الوالدين. */
    private fun maybeConfirm(appLabel: String, onConfirmed: () -> Unit) {
        if (!settings.externalConfirm) {
            onConfirmed()
            return
        }
        mainHandler.post {
            AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.external_confirm_title))
                .setMessage(activity.getString(R.string.external_confirm_message, appLabel))
                .setPositiveButton(activity.getString(R.string.external_confirm_open)) { _, _ -> onConfirmed() }
                .setNegativeButton(activity.getString(R.string.cancel), null)
                .show()
        }
    }

    /**
     * إشعار "لا يوجد تطبيق" الكامل (v1.3.0) — بدل Toast صامت:
     * حوار يعرض الرابط المطلوب وزر نسخه — فتظل الصفحة الحالية واضحة السبب
     * ولا تظهر "تسريب تحميل بلا تفسير".
     * v1.8.0: عند توفر رابط احتياطي مسموح (S.browser_fallback_url) يُضاف زر
     * «فتح الرابط في المتصفح» — نفس سلوك كروم عند غياب التطبيق المستهدف.
     */
    private fun notifyNoApp(scheme: String, data: Uri?, fallbackUrl: String? = null) {
        val link = data?.toString()
        val canOpenFallback = fallbackUrl != null &&
            fallbackNavigator != null &&
            fallbackValidator?.invoke(fallbackUrl) == true
        mainHandler.post {
            val message = if (link.isNullOrBlank()) {
                activity.getString(R.string.toast_no_app)
            } else {
                activity.getString(R.string.external_no_app_message, link)
            }
            AlertDialog.Builder(activity)
                .setTitle(R.string.external_no_app_title)
                .setMessage(message)
                .setPositiveButton(R.string.ok, null)
                .apply {
                    if (canOpenFallback) {
                        setNeutralButton(R.string.external_open_fallback) { _, _ ->
                            fallbackNavigator?.invoke(fallbackUrl!!)
                        }
                    }
                    if (!link.isNullOrBlank()) {
                        // نسخ الرابط يبقى متاحًا دائمًا حتى مع زر الرابط الاحتياطي
                        if (canOpenFallback) {
                            setNegativeButton(R.string.external_copy_link) { _, _ ->
                                copyToClipboard(link)
                                toast(activity.getString(R.string.external_copied))
                            }
                        } else {
                            setNeutralButton(R.string.external_copy_link) { _, _ ->
                                copyToClipboard(link)
                                toast(activity.getString(R.string.external_copied))
                            }
                        }
                    }
                }
                .show()
        }
    }

    private fun copyToClipboard(text: String) {
        try {
            val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("url", text))
        } catch (e: Exception) {
            // تجاهل — الحوافظ المقيدة بلا حافظة
        }
    }

    private fun notifyBlockedExternal() {
        ServiceLocator.applicationScope.launch {
            ServiceLocator.blockedActivityRepository.record(
                url = null,
                host = null,
                reason = "EXTERNAL_APPS_DISABLED",
                navigationType = "EXTERNAL"
            )
        }
        toast(activity.getString(R.string.toast_external_apps_disabled))
    }

    private fun notifyVideoPlayersDisabled() {
        ServiceLocator.applicationScope.launch {
            ServiceLocator.blockedActivityRepository.record(
                url = null,
                host = null,
                reason = "VIDEO_PLAYERS_DISABLED",
                navigationType = "EXTERNAL"
            )
        }
        toast(activity.getString(R.string.toast_video_players_disabled))
    }

    private fun toast(message: String) {
        mainHandler.post {
            Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
        }
    }
}
