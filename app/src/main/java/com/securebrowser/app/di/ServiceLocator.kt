package com.securebrowser.app.di

import android.content.Context
import com.securebrowser.app.browser.BrowserDownloadManager
import com.securebrowser.app.core.url.UrlNormalizer
import com.securebrowser.app.data.db.AppDatabase
import com.securebrowser.app.data.repository.BlockedActivityRepository
import com.securebrowser.app.data.repository.DownloadRepository
import com.securebrowser.app.data.repository.HistoryRepository
import com.securebrowser.app.data.repository.SettingsRepository
import com.securebrowser.app.data.repository.WhiteListRepository
import com.securebrowser.app.parental.ParentalAuthManager
import com.securebrowser.app.policy.InMemoryTempAccessStore
import com.securebrowser.app.policy.PolicyManager
import com.securebrowser.app.policy.TempAccessStore
import com.securebrowser.app.policy.TemporaryAccessManager
import com.securebrowser.app.security.SecurityEngine
import com.securebrowser.app.security.storage.BruteForceProtector
import com.securebrowser.app.security.storage.PinManager
import com.securebrowser.app.security.storage.SecureStorage
import com.securebrowser.app.security.whitelist.WhiteListEngine
import com.securebrowser.app.security.whitelist.WhiteListRuleProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * تجميع اعتماد يدوي (بدون أطر DI) — وضوح كامل لشبكة الاعتماد الأمنية.
 */
object ServiceLocator {

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var database: AppDatabase
        private set

    lateinit var whiteListRepository: WhiteListRepository
        private set
    lateinit var historyRepository: HistoryRepository
        private set
    lateinit var blockedActivityRepository: BlockedActivityRepository
        private set
    lateinit var downloadRepository: DownloadRepository
        private set
    lateinit var settingsRepository: SettingsRepository
        private set

    lateinit var secureStorage: SecureStorage
        private set
    lateinit var pinManager: PinManager
        private set
    lateinit var bruteForceProtector: BruteForceProtector
        private set
    lateinit var parentalAuthManager: ParentalAuthManager
        private set

    lateinit var policyManager: PolicyManager
        private set
    lateinit var whiteListEngine: WhiteListEngine
        private set
    lateinit var securityEngine: SecurityEngine
        private set
    lateinit var temporaryAccess: TemporaryAccessManager
        private set
    lateinit var downloadManager: BrowserDownloadManager
        private set
    /** v1.9.0 — النسخ الاحتياطي والاستعادة الشاملة لكل بيانات المتصفح. */
    lateinit var backupManager: com.securebrowser.app.data.backup.BackupManager
        private set

    @Volatile
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return

            val appContext = context.applicationContext
            database = AppDatabase.build(appContext)

            whiteListRepository = WhiteListRepository(database.whiteListRuleDao())
            historyRepository = HistoryRepository(database.historyDao())
            blockedActivityRepository = BlockedActivityRepository(database.blockedActivityDao())
            downloadRepository = DownloadRepository(database.downloadRecordDao())
            settingsRepository = SettingsRepository(database.appSettingsDao(), applicationScope)

            secureStorage = SecureStorage(appContext)
            pinManager = PinManager(secureStorage)
            bruteForceProtector = BruteForceProtector(secureStorage)
            parentalAuthManager = ParentalAuthManager(pinManager, bruteForceProtector)

            // الوصول المؤقت: انتهاء النافذة مخزَّن مشفرًا — إغلاق التطبيق لا يجعله تجاوزًا دائمًا
            temporaryAccess = TemporaryAccessManager(SecureTempAccessStore(secureStorage))
            policyManager = PolicyManager(temporaryAccess)
            // بوابة مشغلات الفيديو الخارجية → إعداد الوالدين "video_players"
            policyManager.videoPlayersEnabled = { settingsRepository.videoPlayers }

            whiteListEngine = WhiteListEngine(
                WhiteListRuleProvider { whiteListRepository.currentRules() }
            )
            // v1.9.0 — بوابة الشبكة المحلية موصولة بإعداد الوالدين (الافتراضي مفعّل)
            securityEngine = SecurityEngine(
                whiteListEngine,
                policyManager,
                infraHostsProvider = { searchEngineInfraHosts(settingsRepository.searchEngine) },
                localNetworkAllowed = { settingsRepository.allowLocalNetwork }
            )

            downloadManager = BrowserDownloadManager(appContext, downloadRepository, applicationScope)
            // v1.9.0 — مدير النسخ الاحتياطي فوق نفس القاعدة + مدير الرمز (هاش PBKDF2 فقط)
            backupManager = com.securebrowser.app.data.backup.BackupManager(database, pinManager)

            // ترحيل سياسة v1.2.0 (schema 1 → 2): طلب الوالد الصريح "لا حظر لأي تنزيل،
            // فقط تسجيلها" — يُطبَّق مرة واحدة حتى على التثبيتات القديمة التي حفظت
            // قيم block/approval سابقًا. الوالد يستطيع دائمًا التشديد لاحقًا من الشاشة.
            applicationScope.launch {
                settingsRepository.warmUp()
                if (settingsRepository.int("policy_schema", 1) < 2) {
                    settingsRepository.put("apk_policy", "allow")
                    settingsRepository.put("archive_policy", "allow")
                    settingsRepository.put("unknown_policy", "allow")
                    settingsRepository.putInt("policy_schema", 2)
                }
            }

            // استعادة نافذة الوصول المؤقت + نبضة العد التنازلي كل ثانية
            temporaryAccess.restoreFromStore()
            applicationScope.launch {
                while (isActive) {
                    delay(1_000)
                    temporaryAccess.tick()
                }
            }

            initialized = true
        }
    }

    /** اختصار للتحقق السريع من صلاحية عنوان (يستخدمه شريط العنوان قبل التنقل). */
    fun quickNormalize(input: String?) = UrlNormalizer.normalize(input)

    /**
     * مضيف "البنية التحتية" الوحيد المسموح إضافته إلى القائمة البيضاء:
     * محرك البحث المُعدّ حاليًا — بالتطابق التام لمضيفه الوحيد.
     *
     * لماذا؟ لأن البحث نفسه (ميزة من المواصفة) يمر عبر القائمة البيضاء،
     * وبدون هذا الإعفاء الضيق لا يمكن البحث إطلاقًا من أول تشغيل.
     * الضمانات: مضيف واحد من ثلاثة محركات ثابتة فقط، كل نتيجة/توجيه/نافذة
     * تخرج من صفحة المحرك تُحكم عليها من SecurityEngine كالمعتاد.
     */
    private fun searchEngineInfraHosts(engine: String): Set<String> = when (engine) {
        "duckduckgo" -> setOf("duckduckgo.com")
        "google" -> setOf("www.google.com")
        "bing" -> setOf("www.bing.com")
        else -> emptySet()
    }

    /** تخزين انتهاء الوصول المؤقت فوق SecureStorage (مشفر Keystore). */
    private class SecureTempAccessStore(private val storage: SecureStorage) : TempAccessStore {
        override fun putExpiry(expiryEpochMs: Long) {
            storage.putLong(KEY, expiryEpochMs)
        }

        override fun getExpiry(): Long? {
            if (!storage.contains(KEY)) return null
            return storage.getLong(KEY, 0L)
        }

        override fun clear() = storage.remove(KEY)

        companion object {
            private const val KEY = "temp_access_expiry"
        }
    }
}
