package com.securebrowser.app

import android.app.Application
import com.securebrowser.app.di.ServiceLocator
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * نقطة التطبيق — تهيئة كل الطبقات قبل أي نشاط:
 * Security Layer → Policy Layer → Local Data Layer → Secure Storage → Browser Core.
 *
 * مرحلة 2: تحميل الإعدادات مبكرًا لتطبيق السمة/التكبير قبل أول إطار،
 * واستعادة نافذة الوصول المؤقت (لا تتحول إلى تجاوز دائم بعد الإغلاق).
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)

        // تحميل الإعدادات بشكل متزامن مرة واحدة (استعلام واحد سريع) لتطبيق السمة قبل أول نشاط
        runBlocking {
            ServiceLocator.settingsRepository.warmUp()
        }
        applyTheme(ServiceLocator.settingsRepository.theme)

        // تسخين لقطة القائمة البيضاء مبكرًا
        ServiceLocator.applicationScope.launch {
            ServiceLocator.whiteListEngine.refresh()
        }
        // ملاحظة Logging (§14): لا تُسجّل أي بيانات مستخدم حساسة في الإصدار النهائي.
    }

    companion object {
        fun applyTheme(theme: String) {
            val mode = when (theme) {
                "light" -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
                "dark" -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
                else -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
            androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(mode)
        }
    }
}
