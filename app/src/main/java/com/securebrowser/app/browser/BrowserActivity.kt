package com.securebrowser.app.browser

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.widget.EditText
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.color.MaterialColors
import com.securebrowser.app.R
import com.securebrowser.app.core.url.AddressBarClassifier
import com.securebrowser.app.core.url.NormalizeResult
import com.securebrowser.app.core.url.ParsedUrl
import com.securebrowser.app.core.url.UrlNormalizer
import com.securebrowser.app.data.repository.HistoryRepository
import com.securebrowser.app.data.repository.SettingsRepository
import com.securebrowser.app.databinding.ActivityBrowserBinding
import com.securebrowser.app.databinding.ViewBlockScreenBinding
import com.securebrowser.app.databinding.ViewErrorBinding
import com.securebrowser.app.databinding.ViewStartPageBinding
import com.securebrowser.app.di.ServiceLocator
import com.securebrowser.app.policy.ExternalAppPolicy
import com.securebrowser.app.security.SecurityEngine
import com.securebrowser.app.security.model.BlockReason
import com.securebrowser.app.security.model.NavigationDecision
import com.securebrowser.app.security.model.NavigationType
import android.graphics.Rect
import android.os.Build
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * قشرة المتصفح — أكبر مساحة ممكنة للويب، وأدنى منطق أمني ممكن:
 * كل القرارات تأتي من [SecurityEngine] والواجهة تعرضها وتنفذها فقط.
 *
 * مرحلة 2: تبويبات احترافية بمعاينات، شريط ذكي (URL/بحث)، بحث في الصفحة،
 * تكبير، مشاركة، ملء شاشة غامر، شاشة خطأ احترافية، مؤشر الوصول المؤقت،
 * واستعادة الجلسة — وكلها خلف بوابة [SecurityEngine].
 */
class BrowserActivity : AppCompatActivity(), BrowserController {

    private lateinit var binding: ActivityBrowserBinding
    private lateinit var blockBinding: ViewBlockScreenBinding
    private lateinit var startBinding: ViewStartPageBinding
    private lateinit var errorBinding: ViewErrorBinding
    private lateinit var tabsManager: TabsManager
    private lateinit var externalHandler: ExternalNavigationHandler

    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null

    private var fileChooserCallback: android.webkit.ValueCallback<Array<Uri>>? = null
    private val singleFileLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            fileChooserCallback?.onReceiveValue(uri?.let { arrayOf(it) })
            fileChooserCallback = null
        }
    private val multipleFilesLauncher =
        registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            fileChooserCallback?.onReceiveValue(uris.toTypedArray())
            fileChooserCallback = null
        }

    private var lastErrorUrl: String? = null
    private var lastErrorDescription: String? = null

    /** مانع إغراق تنقلات الإطارات الفرعية الخارجية (v1.2.0). */
    private var lastSubframeExternalAt = 0L

    /** إيماءات الحواف (v1.6.0) — بديل الشريط السفلي المحذوف. */
    private lateinit var edgeGestures: EdgeNavGestureDetector

    private val securityEngine get() = ServiceLocator.securityEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBrowserBinding.inflate(layoutInflater)
        setContentView(binding.root)

        externalHandler = ExternalNavigationHandler(
            this,
            // v1.8.0 — زر «فتح الرابط في المتصفح» في حوار عدم وجود التطبيق:
            // الرابط الاحتياطي يمر بالأمان كأي تنقل داخلي (SecurityEngine يقرر).
            fallbackNavigator = { url ->
                lifecycleScope.launch { navigateInternal(url, NavigationType.LINK) }
            },
            fallbackValidator = { url ->
                securityEngine.validate(url, NavigationType.LINK) is NavigationDecision.Allow
            }
        )

        // الطبقات العلوية فوق الويب: صفحة البداية / شاشة الحظر / شاشة الخطأ
        startBinding = ViewStartPageBinding.inflate(layoutInflater)
        binding.contentContainer.addView(
            startBinding.root, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        startBinding.root.isVisible = false
        // v1.7.0 — شبكة بطاقات المواقع المسموحة (3 أعمدة)
        startBinding.sitesGrid.layoutManager = androidx.recyclerview.widget.GridLayoutManager(this, 3)

        blockBinding = ViewBlockScreenBinding.inflate(layoutInflater)
        binding.contentContainer.addView(
            blockBinding.root, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        blockBinding.root.isVisible = false

        errorBinding = ViewErrorBinding.inflate(layoutInflater)
        binding.contentContainer.addView(
            errorBinding.root, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        errorBinding.root.isVisible = false

        tabsManager = TabsManager { tabId ->
            SecureWebViewFactory.create(this, tabId, this)
        }
        tabsManager.onTabsChanged = { updateTabsChip() }

        setupToolbar()
        setupGestures()
        setupOverlays()
        setupFindBar()
        setupBackHandling()
        observeTempAccess()

        // استعادة الجلسة (§5) أو تبويب جديد
        val intentUrl = intent?.getStringExtra(EXTRA_OPEN_URL)
        if (intentUrl != null) {
            tabsManager.createTab(activate = true)
            lifecycleScope.launch { navigateInternal(intentUrl, NavigationType.TYPED) }
        } else {
            restoreSessionOrCreateTab()
        }
        updateTabsChip()
    }

    // ————————————————— استعادة الجلسة —————————————————

    private fun restoreSessionOrCreateTab() {
        val specs = tabsManager.loadSessionSpecs()
        if (specs == null) {
            tabsManager.createTab(activate = true)
            showStartPage()
            return
        }
        var first = true
        for (spec in specs) {
            val tab = tabsManager.createTab(activate = spec.isActive, restoreUrl = spec.url) ?: break
            if (first) {
                first = false
                val url = spec.url
                if (url != null) {
                    lifecycleScope.launch { navigateInternal(url, NavigationType.INITIAL) }
                } else {
                    showStartPage()
                }
            }
        }
        if (tabsManager.tabs.isEmpty()) {
            tabsManager.createTab(activate = true)
            showStartPage()
        }
    }

    // ————————————————— شريط الأدوات —————————————————

    private fun setupToolbar() {
        // v1.6.0: حُذف ربط أزرار الشريط السفلي (btnBack/btnForward/btnHome/
        // btnReload/btnBottomMenu) مع إزالة الشريط نفسه من التخطيط — بدائل:
        // إعادة التحميل = سحب من الأعلى، رجوع/تقدم = سحب من الحواف،
        // الرئيسية/القائمة = قائمة btnMenu العلوية (نفس الإجراءات كاملة).
        binding.btnMenu.setOnClickListener { showBrowserMenu() }
        binding.tabsChip.setOnClickListener { showTabsSheet() }
        binding.btnSiteInfo.setOnClickListener { showSiteInfo() }

        binding.addressBar.setOnEditorActionListener { view, actionId, event ->
            val go = actionId == EditorInfo.IME_ACTION_GO ||
                (event != null && event.action == android.view.KeyEvent.ACTION_DOWN &&
                    event.keyCode == android.view.KeyEvent.KEYCODE_ENTER)
            if (go) {
                onAddressSubmitted(view.text?.toString().orEmpty())
                true
            } else false
        }
        binding.addressBar.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                // يشمل الرابط المحجوب (attemptedUrl) إذا كانت شاشة الحظر معروضة
                binding.addressBar.setText(currentTabUrl() ?: "")
                binding.addressBar.selectAll()
            } else {
                syncAddressBar()
            }
        }
    }

    // ————————————————— إيماءات v1.6.0: سحب للتحديث + حواف للتنقل —————————————————

    private fun setupGestures() {
        binding.swipeRefresh.setColorSchemeColors(
            MaterialColors.getColor(
                binding.root,
                androidx.appcompat.R.attr.colorPrimary,
                androidx.core.content.ContextCompat.getColor(this, android.R.color.black)
            )
        )
        binding.swipeRefresh.setOnRefreshListener {
            if (currentTabState().isStartPage) {
                binding.swipeRefresh.isRefreshing = false
                return@setOnRefreshListener
            }
            performReload()
        }
        // المفوّض يجعل السحب للتحديث يعمل فقط في أعلى الصفحة — الابن المباشر
        // FrameLayout وسيط لا يعرف حالة تمرير WebView
        binding.swipeRefresh.canScrollUpDelegate = {
            activeWebView()?.canScrollVertically(-1) ?: false
        }
        edgeGestures = EdgeNavGestureDetector(
            host = binding.contentContainer,
            onBack = { navBack() },
            onForward = { navForward() }
        )
        updateSwipeEnabled()
        // v1.8.0 — حجز شرائط الحواف من إيماءة النظام (Android 10+):
        // بدون هذا كانت إيماءة التنقل الرأسية للنظام تستهلك السحب الجانبي
        // قبل وصوله للتطبيق أصلًا (ACTION_CANCEL) فلا تعمل الإيماءة إطلاقًا.
        binding.contentContainer.doOnLayout { applySystemGestureExclusion() }
    }

    /**
     * v1.8.0 — استبعاد إيماءات النظام من شرائط الحواف اليسرى/اليمنى
     * (setSystemGestureExclusionRects — Android 10+):
     * شرط بعرض EDGE تقريبًا بطول مركزي 200dp (الحد الأقصى الذي يكرمه النظام
     * لكل حافة) — كي تبقى إيماءة الرجوع/التقدم الخاصة بالتطبيق عاملة مع
     * التنقل بالإيماءات، مع إبقاء أعلى/أسفل الشاشة للنظام.
     * تُعاد عند كل تركيز للنافذة (توصية الدليل الرسمي) وتُلغى في ملء الشاشة.
     */
    private fun applySystemGestureExclusion() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val container = binding.contentContainer
        if (fullscreenView != null) {
            container.systemGestureExclusionRects = emptyList()
            return
        }
        val w = container.width
        val h = container.height
        if (w <= 0 || h <= 0) return
        val density = resources.displayMetrics.density
        val strip = (GESTURE_EXCLUSION_WIDTH_DP * density).toInt()
        val height = minOf(h, (GESTURE_EXCLUSION_MAX_HEIGHT_DP * density).toInt())
        val top = ((h - height) / 2f).toInt()
        container.systemGestureExclusionRects = listOf(
            Rect(0, top, strip, top + height),
            Rect(w - strip, top, w, top + height)
        )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applySystemGestureExclusion()
    }

    /** هل الإيماءات الجانبية مسموحة الآن؟ (باطلة في ملء الشاشة/بحث الصفحة/صفحة البداية) */
    private val edgeGesturesAllowed: Boolean
        get() = fullscreenView == null &&
            !binding.findBar.isVisible &&
            !startBinding.root.isVisible

    /**
     * v1.6.0: مراقبة أحداث اللمس لإيماءات الحواف **بلا استهلاك** — تعيد دائمًا
     * التحكم للسلسلة الأصلية فيبقى WebView تفاعليًا بالكامل (نقر، تمرير، تحديد).
     */
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (edgeGesturesAllowed) edgeGestures.observe(ev)
        return super.dispatchTouchEvent(ev)
    }

    /** رجوع عبر إيماءة الحافة — نفس سلوك زر الرجوع القديم مع شاشة الحظر. */
    private fun navBack() {
        if (errorBinding.root.isVisible) hideErrorOverlay()
        if (blockBinding.root.isVisible) {
            hideBlockOverlay()
            val wv = activeWebView()
            if (wv?.canGoBack() == true) wv.goBack() else showStartPage()
            return
        }
        activeWebView()?.goBack()
    }

    /** تقدم عبر إيماءة الحافة المقابلة. */
    private fun navForward() {
        activeWebView()?.goForward()
    }

    /**
     * تفعيل السحب للتحديث فقط عندما تكون منطقة الويب هي المعروضة فعلاً —
     * تُعطّل فوق صفحات البداية/الحظر/الخطأ حتى لا يلتقط السحب أحداثها.
     */
    private fun updateSwipeEnabled() {
        binding.swipeRefresh.isEnabled =
            !startBinding.root.isVisible &&
            !blockBinding.root.isVisible &&
            !errorBinding.root.isVisible
    }

    private fun performReload() {
        hideErrorOverlay()
        val wv = activeWebView()
        if (wv != null && !currentTabState().isStartPage) {
            wv.reload()
        } else {
            binding.swipeRefresh.isRefreshing = false
        }
    }

    private fun onAddressSubmitted(raw: String) {
        binding.addressBar.clearFocus()
        if (raw.isBlank()) return
        lifecycleScope.launch {
            ServiceLocator.whiteListEngine.refresh()
            val input = raw.trim()
            val target = resolveInput(input)
            when (target) {
                is ResolvedInput.Url -> navigateInternal(target.url, NavigationType.TYPED)
                is ResolvedInput.Search -> navigateInternal(target.searchUrl, NavigationType.TYPED)
            }
        }
    }

    /**
     * شريط العنوان الذكي (§4): URL → فحص WhiteList ثم فتح؛
     * نص → محرك البحث (والنتائج ليست استثناءً من القائمة — §24).
     *
     * التصنيف عبر AddressBarClassifier: الكلمات المفردة والعبارات (عربي/إنجليزي)
     * تذهب إلى البحث ولا تتحول إلى نطاقات وهمية تضاف إليها https:// تلقائيًا.
     */
    private fun resolveInput(input: String): ResolvedInput {
        if (AddressBarClassifier.isLikelyUrl(input)) {
            return ResolvedInput.Url(input)
        }

        // نص بحث → قالب محرك البحث — نفس المسار الأمني تمامًا
        val engine = ServiceLocator.settingsRepository.searchEngine
        val searchUrl = SettingsRepository.searchUrlFor(engine, input)
            ?: SettingsRepository.searchUrlFor("duckduckgo", input)!!
        return ResolvedInput.Search(searchUrl)
    }

    private sealed class ResolvedInput {
        class Url(val url: String) : ResolvedInput()
        class Search(val searchUrl: String) : ResolvedInput()
    }

    private fun setupOverlays() {
        blockBinding.btnBlockBack.setOnClickListener {
            hideBlockOverlay()
            val wv = activeWebView()
            if (wv?.canGoBack() == true) wv.goBack() else showStartPage()
        }
        blockBinding.btnBlockPrevious.setOnClickListener {
            hideBlockOverlay()
            val wv = activeWebView()
            if (wv?.canGoBack() == true) wv.goBack() else showStartPage()
        }
        errorBinding.btnErrorRetry.setOnClickListener {
            hideErrorOverlay()
            performReload()
        }
        errorBinding.btnErrorBack.setOnClickListener {
            hideErrorOverlay()
            val wv = activeWebView()
            if (wv?.canGoBack() == true) wv.goBack() else showStartPage()
        }
        errorBinding.btnErrorDetails.setOnClickListener {
            val visible = !errorBinding.errorDetailsContainer.isVisible
            errorBinding.errorDetailsContainer.isVisible = visible
            errorBinding.btnErrorDetails.setText(
                if (visible) R.string.error_details_hide else R.string.error_details_show
            )
        }
    }

    /** ربط أزرار شريط "بحث داخل الصفحة" (§22). */
    private fun setupFindBar() {
        binding.btnFindClose.setOnClickListener { closeFindInPage() }
        binding.btnFindPrev.setOnClickListener {
            findOnPage(binding.findInput.text?.toString().orEmpty(), forward = false, newSearch = false)
        }
        binding.btnFindNext.setOnClickListener {
            findOnPage(binding.findInput.text?.toString().orEmpty(), forward = true, newSearch = false)
        }
    }

    private fun setupBackHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    fullscreenView != null -> exitFullscreen()
                    binding.findBar.isVisible -> closeFindInPage()
                    errorBinding.root.isVisible -> hideErrorOverlay()
                    blockBinding.root.isVisible -> {
                        hideBlockOverlay()
                        val wv = activeWebView()
                        if (wv?.canGoBack() == true) wv.goBack() else showStartPage()
                    }
                    activeWebView()?.canGoBack() == true -> activeWebView()?.goBack()
                    startBinding.root.isVisible -> moveTaskToBack(true)
                    else -> showStartPage()
                }
            }
        })
    }

    private fun activeWebView(): WebView? = tabsManager.activeWebView()

    private fun currentTabState(): TabUiState = tabsManager.activeTab?.uiState ?: TabUiState()

    // ————————————————— الوصول المؤقت — مؤشر حي —————————————————

    private fun observeTempAccess() {
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                ServiceLocator.temporaryAccess.uiState.collect { state ->
                    binding.tempBanner.isVisible = state.active
                    if (state.active) {
                        binding.tempBannerCountdown.text = formatCountdown(state.remainingMs)
                    }
                }
            }
        }
        binding.btnTempEnd.setOnClickListener {
            ServiceLocator.temporaryAccess.endNow()
            Toast.makeText(this, R.string.temp_ended, Toast.LENGTH_SHORT).show()
        }
    }

    private fun formatCountdown(ms: Long): String {
        val totalSeconds = ms / 1000
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) {
            String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(java.util.Locale.US, "%02d:%02d", m, s)
        }
    }

    // ————————————————— التنقل —————————————————

    /** تنقل داخلي: كل مسار يمر هنا → refresh → SecurityEngine → تنفيذ القرار. */
    private suspend fun navigateInternal(rawUrl: String, type: NavigationType) {
        when (val decision = securityEngine.validate(rawUrl, type)) {
            is NavigationDecision.Allow -> loadAllowed(decision.url)
            is NavigationDecision.OpenExternal ->
                externalHandler.handle(decision.scheme, decision.target)
            is NavigationDecision.Block -> {
                recordBlockedNavigation(decision.rawUrl, decision.reason, type)
                val suggestion = ExternalAppPolicy.suggestForWebUrl(decision.rawUrl ?: rawUrl)
                if (decision.reason == BlockReason.NOT_WHITELISTED && suggestion != null) {
                    // رابط تطبيق معروف يحظرته القائمة → خيار "فتح في التطبيق" (§19)
                    externalHandler.handleBlockedWebUrl(decision.rawUrl ?: rawUrl) {
                        showErrorBlockScreen(decision.rawUrl ?: rawUrl, decision.detail)
                    }
                } else {
                    showErrorBlockScreen(decision.rawUrl ?: rawUrl, decision.detail)
                }
            }
        }
    }

    private fun showErrorBlockScreen(rawUrl: String?, hostOrDetail: String?) {
        // v1.6.0 — لحظة الحظر هي لحظة مزامنة العنوان: كان الشريط يبقى على
        // عنوان الصفحة السابقة لأن WebView لم يبدأ تحميل الرابط المحجوب أصلًا،
        // فكانت «إضافة للقائمة البيضاء» تلتقط العنوان الخاطئ. الآن يُسجّل
        // رابط المحاولة فورًا ويُعرض في الشريط ويُلتقط لكل المسارات.
        if (!rawUrl.isNullOrBlank()) markAttemptedUrl(rawUrl)
        binding.swipeRefresh.isRefreshing = false
        hideErrorOverlay()
        val label = listOfNotNull(
            hostOrDetail?.takeIf { it.isNotBlank() },
            rawUrl?.takeIf { it.isNotBlank() && it != hostOrDetail }
        ).joinToString("\n")
        blockBinding.blockedHost.isVisible = label.isNotBlank()
        blockBinding.blockedHost.text = label
        blockBinding.root.isVisible = true
        updateSwipeEnabled()
    }

    /**
     * تسجيل رابط المحاولة على التبويب النشط وعرضه في شريط العنوان فورًا
     * (v1.6.0 — جذر إصلاح مزامنة URL المحجوب).
     */
    private fun markAttemptedUrl(url: String) {
        tabsManager.activeTab?.attemptedUrl = url
        if (!binding.addressBar.hasFocus()) binding.addressBar.setText(url)
    }

    /** فتح من خارج النشاط (سجل/إجراءات) — مع تبويب جديد اختياري. */
    fun navigateFromExternal(url: String, newTab: Boolean) {
        lifecycleScope.launch {
            ServiceLocator.whiteListEngine.refresh()
            if (newTab) {
                val tab = tabsManager.createTab(activate = true) ?: run {
                    Toast.makeText(this@BrowserActivity, R.string.toast_tabs_limit, Toast.LENGTH_SHORT).show()
                    return@launch
                }
                attachActiveTab()
                when (val decision = securityEngine.validate(url, NavigationType.LINK)) {
                    is NavigationDecision.Allow -> loadAllowed(decision.url)
                    is NavigationDecision.OpenExternal ->
                        externalHandler.handle(decision.scheme, decision.target)
                    is NavigationDecision.Block -> {
                        recordBlockedNavigation(decision.rawUrl, decision.reason, NavigationType.LINK)
                        showErrorBlockScreen(decision.rawUrl ?: url, decision.detail)
                    }
                }
                tab.update { it.copy(isStartPage = false) }
            } else {
                navigateInternal(url, NavigationType.LINK)
            }
        }
    }

    private fun loadAllowed(url: ParsedUrl) {
        hideBlockOverlay()
        hideErrorOverlay()
        hideStartPage()
        // بدأ تحميل صفحة حقيقية — رابط المحاولة المحجوب لم يعد ذا صلة
        tabsManager.activeTab?.attemptedUrl = null
        val target = if (url.isWeb) url.canonical() else url.raw
        activeWebView()?.loadUrl(target)
    }

    override fun securityEngine(): SecurityEngine = securityEngine

    override fun handleMainFrameDecision(
        view: WebView,
        decision: NavigationDecision,
        rawUrl: String,
        type: NavigationType
    ): Boolean {
        return when (decision) {
            is NavigationDecision.Allow -> {
                if (!decision.url.isWeb) return false // blob: إلخ — يكمل المحرك مساره الأصلي
                if (type == NavigationType.LINK &&
                    isSamePageIgnoringFragment(view.url, decision.url)
                ) {
                    return false
                }
                hideBlockOverlay()
                hideErrorOverlay()
                hideStartPage()
                view.loadUrl(decision.url.canonical())
                true
            }

            is NavigationDecision.OpenExternal -> {
                // إخماد فوري لمؤشر التقدم — التنقل أُلغي لصالح تطبيق خارجي
                // (كان قد يظل عالقًا ظاهرًا "تحميلًا بلا توقف" على بعض الأجهزة)
                if (view == activeWebView()) runOnUiThread { binding.progressBar.isVisible = false }
                externalHandler.handle(decision.scheme, decision.target)
                true
            }

            is NavigationDecision.Block -> {
                if (view == activeWebView()) runOnUiThread { binding.progressBar.isVisible = false }
                recordBlockedNavigation(decision.rawUrl ?: rawUrl, decision.reason, type)
                val suggestion = ExternalAppPolicy.suggestForWebUrl(decision.rawUrl ?: rawUrl)
                if (decision.reason == BlockReason.NOT_WHITELISTED && suggestion != null &&
                    view == activeWebView()
                ) {
                    externalHandler.handleBlockedWebUrl(decision.rawUrl ?: rawUrl) {
                        showErrorBlockScreen(decision.rawUrl ?: rawUrl, decision.detail)
                    }
                } else if (view == activeWebView()) {
                    showErrorBlockScreen(decision.rawUrl ?: rawUrl, decision.detail)
                }
                true
            }
        }
    }

    private fun isSamePageIgnoringFragment(currentRaw: String?, target: ParsedUrl): Boolean {
        if (currentRaw.isNullOrBlank()) return false
        val current = UrlNormalizer.normalize(currentRaw)
        return (current as? NormalizeResult.Success)?.url?.let { cur ->
            cur.host == target.host && cur.path == target.path &&
                cur.query == target.query && cur.port == target.port
        } == true
    }

    override fun verifyCommittedPage(view: WebView, url: String?) {
        if (url.isNullOrBlank()) return
        when (val decision = securityEngine.validate(url, NavigationType.JS)) {
            is NavigationDecision.Block -> {
                view.stopLoading()
                recordBlockedNavigation(url, decision.reason, NavigationType.JS)
                if (view == activeWebView()) {
                    showErrorBlockScreen(url, decision.detail)
                }
            }
            else -> Unit
        }
    }

    // ————————————————— النوافذ المنبثقة — تبويبات عبر الأمان —————————————————

    override fun handleCreateWindow(
        view: WebView,
        isDialog: Boolean,
        isUserGesture: Boolean,
        resultMsg: Message?
    ): Boolean = true // الاعتراض الفعلي في SecureWebChromeClient.onCreateWindow

    /**
     * v1.4.0 — معمارية اعتماد النافذة: أول تنقل لنافذة popup يُقرَّر هنا
     * **متزامنًا** (بلا coroutine — كانت مهلة/تأخير التقاط v1.3.0 سبب
     * "لا شيء يحدث")، وعند السماح **نفس WebView النافذة** يُعتمد تبويبًا
     * حقيقيًا ويستمر التحميل الأصلي دون إلغاء أو إعادة تحميل:
     *  - يحفظ بيانات POST (نماذج التسجيل التي تُرسل عبر نافذة جديدة).
     *  - يبقي مرجع window حيًا عند الموقع (document.write / OAuth postMessage).
     *  - يعرض محتوى blob المولّد (ملفات/معاينات) داخل التبويب المعتمد.
     * العملاء الحقيقيان (SecureWebViewClient/SecureWebChromeClient) يُربطان
     * بمعرف التبويب الجديد فورًا — كل تنقل لاحق يمر عبر SecurityEngine كأي تبويب.
     */
    override fun handlePopupFirstNavigation(
        webView: WebView,
        rawUrl: String,
        type: NavigationType,
        isUserGesture: Boolean
    ): Boolean {
        val decision = securityEngine.validate(rawUrl, type)
        return when (decision) {
            is NavigationDecision.Allow -> {
                val tab = tabsManager.createTabWithWebView(webView, activate = true) ?: run {
                    Toast.makeText(this, R.string.toast_tabs_limit, Toast.LENGTH_SHORT).show()
                    return true // لم يُعتمد — القشرة تتولى التدمير
                }
                // ربط العملاء الحقيقيين بمعرف التبويب الجديد قبل استمرار التحميل
                webView.webViewClient = SecureWebViewClient(tab.id, this)
                webView.webChromeClient = SecureWebChromeClient(tab.id, this)
                SecureWebViewFactory.attachDownloadListener(webView, this)
                tab.update {
                    it.copy(
                        url = if (decision.url.isWeb) decision.url.canonical() else decision.url.raw,
                        isStartPage = false
                    )
                }
                attachActiveTab()
                hideStartPage()
                false // استمرار التحميل الأصلي داخل التبويب المعتمد
            }

            is NavigationDecision.OpenExternal -> {
                externalHandler.handle(decision.scheme, decision.target)
                true
            }

            is NavigationDecision.Block -> {
                recordBlockedNavigation(decision.rawUrl ?: rawUrl, decision.reason, type)
                showErrorBlockScreen(decision.rawUrl ?: rawUrl, decision.detail)
                true
            }
        }
    }

    // ————————————————— حالة الصفحة —————————————————

    override fun onHistoryStateChanged(view: WebView, url: String?, isReload: Boolean) {
        if (view != activeWebView()) return
        runOnUiThread {
            // إصلاح v1.2.0: كان هذا المعال يتجاهل الرابط الجديد كليًا ويقرأ الحالة القديمة
            // فبقى شريط العنوان على العنوان السابق بعد إعادة التوجيه/تنقلات SPA
            // (pushState/replaceState — مثل oauth.telegram.org) فلا يمكن إضافة النطاق
            // الجديد إلى القائمة البيضاء. الآن: الحالة والشريط يتحدثان من الرابط الحي.
            if (!url.isNullOrBlank()) {
                tabsManager.find(idForWebView(view))?.update { it.copy(url = url) }
            }
            view.updateNavigationState()
            syncAddressBar()
        }
    }

    override fun onPageStarted(view: WebView, url: String?) {
        if (view != activeWebView()) return
        runOnUiThread {
            if (!url.isNullOrBlank()) {
                view.updateNavigationState()
                binding.addressBar.setText(url)
                binding.progressBar.isVisible = true
                binding.swipeRefresh.isRefreshing = false
                hideErrorOverlay()
                // صفحة حقيقية بدأ تحميلها — امسح رابط المحاولة المحجوب
                tabsManager.find(idForWebView(view))?.attemptedUrl = null
                tabsManager.activeTab?.update { it.copy(isLoading = true, url = url) }
            }
        }
    }

    override fun onPageFinished(view: WebView, url: String?, title: String?) {
        if (view != activeWebView()) return
        runOnUiThread {
            view.updateNavigationState()
            binding.progressBar.isVisible = false
            binding.swipeRefresh.isRefreshing = false
            syncAddressBar()
            tabsManager.activeTab?.update {
                it.copy(isLoading = false, url = url ?: it.url, title = title ?: it.title)
            }
            tabsManager.activeTab?.capturePreview()
            tabsManager.saveSession()
        }
        // سجل التصفح: الصفحات المسموحة فقط (المحظورة في جدول آخر) —
        // الزيارات أثناء نافذة الوصول المؤقت تُعلَّم TEMPORARY (§7)
        val parsed = UrlNormalizer.normalize(url)
        if (parsed is NormalizeResult.Success && parsed.url.isWeb) {
            ServiceLocator.applicationScope.launch {
                val tempActive = ServiceLocator.temporaryAccess.isActive()
                ServiceLocator.historyRepository.record(
                    url = parsed.url.canonical(),
                    title = title,
                    host = parsed.url.host,
                    visitResult = if (tempActive) HistoryRepository.RESULT_TEMPORARY
                    else HistoryRepository.RESULT_ALLOWED
                )
            }
        }
    }

    override fun onProgressChanged(tabId: Long, progress: Int) {
        if (tabsManager.activeTab?.id != tabId) return
        runOnUiThread {
            if (progress >= 100) {
                binding.progressBar.progress = 100
                binding.progressBar.isVisible = false
            } else {
                binding.progressBar.isVisible = true
                binding.progressBar.progress = progress.coerceIn(0, 100)
            }
        }
    }

    override fun onSecurityError(url: String, reason: String) {
        recordBlockedNavigation(url, BlockReason.SECURITY_ERROR, NavigationType.JS)
        runOnUiThread {
            showErrorOverlay(
                getString(R.string.error_subtitle_ssl),
                "SSL: $url"
            )
        }
    }

    /** شاشة الخطأ الاحترافية (§30) — لا رسائل WebView تقنية مباشرة. */
    override fun onPageError(url: String?, description: String?) {
        ServiceLocator.applicationScope.launch {
            ServiceLocator.blockedActivityRepository.record(
                url = url,
                host = hostFromRaw(url),
                reason = "PAGE_ERROR",
                navigationType = NavigationType.JS.name
            )
        }
        runOnUiThread {
            if (activeWebView()?.url != url && activeWebView()?.url != null) return@runOnUiThread
            val subtitle = when {
                description?.contains("timeout", ignoreCase = true) == true ->
                    getString(R.string.error_subtitle_timeout)
                description?.contains("ssl", ignoreCase = true) == true ||
                    description?.contains("certificate", ignoreCase = true) == true ->
                    getString(R.string.error_subtitle_ssl)
                description?.contains("server", ignoreCase = true) == true ->
                    getString(R.string.error_subtitle_server)
                else -> getString(R.string.error_subtitle_network)
            }
            showErrorOverlay(subtitle, "$url\n\n$description")
        }
    }

    private fun showErrorOverlay(subtitle: String, details: String?) {
        hideBlockOverlay()
        errorBinding.errorSubtitle.text = subtitle
        errorBinding.errorDetailsText.text = details ?: ""
        errorBinding.errorDetailsContainer.isVisible = false
        errorBinding.btnErrorDetails.setText(R.string.error_details_show)
        errorBinding.root.isVisible = true
        updateSwipeEnabled()
    }

    private fun hideErrorOverlay() {
        if (errorBinding.root.isVisible) errorBinding.root.isVisible = false
        updateSwipeEnabled()
    }

    private fun WebView.updateNavigationState() {
        tabsManager.find(idForWebView(this))?.update {
            it.copy(
                canGoBack = this.canGoBack(),
                canGoForward = this.canGoForward(),
                isStartPage = false
            )
        }
    }

    private fun idForWebView(webView: WebView): Long =
        tabsManager.tabs.firstOrNull { it.webView == webView }?.id ?: -1L

    /**
     * مصدر الحقيقة لشريط العنوان (v1.6.0):
     * 1. أثناء عرض شاشة الحظر: رابط المحاولة المحجوب (attemptedUrl) أولًا —
     *    هذا ما يراه المستخدم أمامه فيجب أن يعكسه الشريط.
     * 2. غير ذلك: رابط WebView الحي (يعكس إعادة التوجيه وتنقلات pushState
     *    لحظيًا)، ثم الحالة المحفوظة احتياطًا (صفحة بداية/تبويب لم يحمّل).
     */
    private fun syncAddressBar() {
        if (binding.addressBar.hasFocus()) return
        val attempted = tabsManager.activeTab?.attemptedUrl
        val live = activeWebView()?.url
        binding.addressBar.setText(
            when {
                blockBinding.root.isVisible && !attempted.isNullOrBlank() -> attempted
                !live.isNullOrBlank() -> live
                else -> currentTabState().url
            }.orEmpty()
        )
    }

    private fun updateTabsChip() {
        binding.tabsChip.text = tabsManager.tabs.size.coerceAtMost(99).toString()
    }

    // ————————————————— شاشة الحظر —————————————————

    override fun showBlockScreen(url: String?, host: String?, reason: BlockReason, type: NavigationType) {
        runOnUiThread { showErrorBlockScreen(url, host) }
    }

    private fun hideBlockOverlay() {
        blockBinding.root.isVisible = false
        updateSwipeEnabled()
    }

    override fun recordBlockedNavigation(
        url: String?,
        reason: com.securebrowser.app.security.model.BlockReason,
        type: NavigationType
    ) {
        ServiceLocator.applicationScope.launch {
            ServiceLocator.blockedActivityRepository.record(
                url = url?.take(2048),
                host = hostFromRaw(url),
                reason = reason.name,
                navigationType = type.name
            )
        }
    }

    private fun hostFromRaw(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return when (val r = UrlNormalizer.normalize(raw)) {
            is NormalizeResult.Success -> r.url.host.ifEmpty { null }
            is NormalizeResult.Invalid -> null
        }
    }

    // ————————————————— صفحة البداية —————————————————

    private fun showStartPage() {
        hideBlockOverlay()
        hideErrorOverlay()
        startBinding.root.isVisible = true
        updateSwipeEnabled()
        tabsManager.activeTab?.update { it.copy(isStartPage = true) }
        tabsManager.activeTab?.attemptedUrl = null
        activeWebView()?.let { it.stopLoading() }
        lifecycleScope.launch {
            val rules = ServiceLocator.whiteListRepository.currentRules().filter { it.enabled }
            // v1.7.0 — شبكة مواقع احترافية بأيقونات حقيقية بدل أزرار نصية
            startBinding.sitesCount.text = getString(R.string.start_sites_count, rules.size)
            startBinding.emptyMessage.isVisible = rules.isEmpty()
            startBinding.sitesGrid.isVisible = rules.isNotEmpty()
            startBinding.sitesGrid.adapter = StartSitesAdapter(
                rules,
                onOpen = { host ->
                    lifecycleScope.launch {
                        ServiceLocator.whiteListEngine.refresh()
                        navigateInternal("https://$host", NavigationType.INITIAL)
                    }
                },
                onRemove = { rule -> confirmRemoveSite(rule) }
            )
        }
    }

    /**
     * v1.7.0 — إزالة موقع من صفحة البداية (ضغطة طويلة على البطاقة):
     * تأكيد صريح ثم حذف القاعدة وتحديث المحرك وإعادة رسم الصفحة.
     */
    private fun confirmRemoveSite(rule: com.securebrowser.app.security.whitelist.WhiteListRule) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.remove_site_title))
            .setMessage(getString(R.string.remove_site_message, rule.host))
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch {
                    ServiceLocator.whiteListRepository.delete(rule)
                    ServiceLocator.whiteListEngine.refresh()
                    Toast.makeText(this@BrowserActivity, R.string.site_removed, Toast.LENGTH_SHORT).show()
                    showStartPage()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun hideStartPage() {
        if (startBinding.root.isVisible) {
            startBinding.root.isVisible = false
            tabsManager.activeTab?.update { it.copy(isStartPage = false) }
        }
        updateSwipeEnabled()
    }

    // ————————————————— بحث داخل الصفحة (§22) —————————————————

    fun startFindInPage() {
        binding.findBar.isVisible = true
        binding.findInput.requestFocus()
        activeWebView()?.setFindListener { activeMatchOrdinal, numberOfMatches, isDoneCounting ->
            binding.findCount.text = getString(
                R.string.find_count,
                if (isDoneCounting && numberOfMatches > 0) activeMatchOrdinal + 1 else 0,
                numberOfMatches
            )
        }
        binding.findInput.setOnEditorActionListener { view, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                findOnPage(view.text?.toString().orEmpty(), forward = true, newSearch = true)
                true
            } else false
        }
        binding.findInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                findOnPage(s?.toString().orEmpty(), forward = true, newSearch = true)
            }
        })
    }

    private fun findOnPage(query: String, forward: Boolean, newSearch: Boolean) {
        val wv = activeWebView() ?: return
        if (query.isBlank()) {
            wv.clearMatches()
            binding.findCount.text = getString(R.string.find_count, 0, 0)
            return
        }
        if (newSearch) wv.findAllAsync(query)
        else if (forward) wv.findNext(true) else wv.findNext(false)
    }

    private fun closeFindInPage() {
        binding.findBar.isVisible = false
        activeWebView()?.clearMatches()
        hideKeyboard()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
        currentFocus?.let { imm?.hideSoftInputFromWindow(it.windowToken, 0) }
    }

    // ————————————————— التكبير (§17) —————————————————

    fun changeZoom(delta: Int = 0, reset: Boolean = false) {
        val settings = ServiceLocator.settingsRepository
        val current = settings.zoom
        val target = if (reset) 100 else (current + delta).coerceIn(
            SettingsRepository.MIN_ZOOM, SettingsRepository.MAX_ZOOM
        )
        lifecycleScope.launch { settings.putInt("zoom", target) }
        for (tab in tabsManager.tabs) tab.webView.settings.textZoom = target
        Toast.makeText(this, getString(R.string.zoom_percent, target), Toast.LENGTH_SHORT).show()
    }

    // ————————————————— المشاركة (§21) —————————————————

    fun sharePage() {
        val url = currentTabUrl() ?: return
        shareText(url)
    }

    fun shareText(text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        try {
            startActivity(
                Intent.createChooser(intent, getString(R.string.menu_share))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            // لا مستلم للمشاركة
        }
    }

    fun copyToClipboard(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("url", text))
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    fun currentTabUrl(): String? {
        // v1.6.0: أثناء عرض شاشة الحظر يكون الرابط المعروض للمستخدم هو رابط
        // المحاولة المحجوب — القائمة البيضاء ومعلومات الموقع والمشاركة تلتقطه
        // بدل عنوان الصفحة السابقة الذي كان يُلتقط خطأً (جذر شكوى المزامنة).
        if (blockBinding.root.isVisible) {
            tabsManager.activeTab?.attemptedUrl?.takeIf { it.isNotBlank() }
                ?.let { return it }
        }
        return activeWebView()?.url?.takeIf { it.isNotBlank() }
            ?: currentTabState().url
            ?: tabsManager.activeTab?.pendingRestoreUrl
    }

    // ————————————————— ملء الشاشة الغامر (§16) —————————————————

    override fun enterFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) {
        runOnUiThread {
            fullscreenCallback = callback
            fullscreenView = view
            binding.fullscreenContainer.addView(
                view,
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            binding.fullscreenContainer.isVisible = true
            binding.mainColumn.isVisible = false
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            hideSystemBars()
        }
    }

    override fun exitFullscreen() {
        runOnUiThread {
            binding.fullscreenContainer.removeAllViews()
            binding.fullscreenContainer.isVisible = false
            binding.mainColumn.isVisible = true
            fullscreenView = null
            fullscreenCallback?.onCustomViewHidden()
            fullscreenCallback = null
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            showSystemBars()
        }
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun showSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, true)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.show(WindowInsetsCompat.Type.systemBars())
    }

    // ————————————————— رفع الملفات (§15) —————————————————

    override fun onShowFileChooser(
        webView: WebView?,
        callback: android.webkit.ValueCallback<Array<Uri>>?,
        params: WebChromeClient.FileChooserParams?
    ): Boolean {
        fileChooserCallback?.onReceiveValue(null)
        fileChooserCallback = callback
        return try {
            val multiple = params?.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE
            if (multiple) multipleFilesLauncher.launch("*/*") else singleFileLauncher.launch("*/*")
            true
        } catch (e: Exception) {
            fileChooserCallback = null
            false
        }
    }

    // ————————————————— تبويبات —————————————————

    override fun closeTabIfPossible(tabId: Long) {
        runOnUiThread {
            tabsManager.closeTab(tabId)
            attachActiveTab()
        }
    }

    fun tabsManager(): TabsManager = tabsManager

    fun onNewTabRequested() {
        val tab = tabsManager.createTab(activate = true)
        if (tab == null) {
            Toast.makeText(this, R.string.toast_tabs_limit, Toast.LENGTH_SHORT).show()
            return
        }
        attachActiveTab()
        showStartPage()
    }

    fun onHomeRequested() {
        val homepage = ServiceLocator.settingsRepository.homepage
        if (homepage == "start") showStartPage()
        else lifecycleScope.launch { navigateInternal(homepage, NavigationType.INITIAL) }
    }

    fun attachActiveTab() {
        // تبديل التبويب يبدّل السياق: الطبقات المعروضة ورابط المحاولة تعود
        // للتبويب الجديد — إخفاؤها ومسح الرابط يمنع عرض حظر/خطأ تبويب قديم
        hideBlockOverlay()
        hideErrorOverlay()
        tabsManager.activeTab?.attemptedUrl = null
        binding.webViewContainer.removeAllViews()
        val tab = tabsManager.activeTab
        if (tab == null) {
            showStartPage()
            return
        }
        binding.webViewContainer.addView(
            tab.webView,
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        // تبويب مستعاد لم يُحمَّل بعد
        val pending = tab.pendingRestoreUrl
        if (pending != null) {
            tab.pendingRestoreUrl = null
            lifecycleScope.launch { navigateInternal(pending, NavigationType.INITIAL) }
        }
        updateTabsChip()
        syncAddressBar()
    }

    /** للحوارات بعد إغلاق/تبديل تبويب. */
    fun attachActiveTabForDialog() = attachActiveTab()

    fun syncAddressBarForDialog() = syncAddressBar()

    override fun currentSourceUrl(): String? = currentTabUrl()

    /**
     * تنقل خارجي/غير قابل للعرض من إطار فرعي (iframe) — شبكة أمان v1.2.0.
     *
     * كانت الإطارات الفرعية تُترك للمحرك مهما كان المخطط، فعندما تُوجّه صفحة
     * iframe داخليًا إلى tg:// (مثل تسجيل الدخول عبر تلجرام) كان Chromium يحاول
     * تحميل ما لا يستطيع عرضه فتبقى الصفحة "تحمّل" للأبد.
     *
     * المعالجة: قرار SecurityEngine كامل — OpenExternal → نفس بوابة التطبيقات
     * الخارجية (الإعدادات + مشغلات الفيديو + التأكيد). الحظر يُسجّل فقط دون
     * مقاطعة الصفحة بشاشة الحظر (الإطار الفرعي ليس قرار المستخدم).
     * مانع إغراق (debounce) يمنع إطارات خبيثة من تكرار الحوارات.
     */
    override fun handleSubframeExternal(rawUrl: String) {
        val now = System.currentTimeMillis()
        if (now - lastSubframeExternalAt < SUBFRAME_EXTERNAL_DEBOUNCE_MS) return
        lastSubframeExternalAt = now
        lifecycleScope.launch {
            when (val decision = securityEngine.validate(rawUrl, NavigationType.JS)) {
                is NavigationDecision.OpenExternal ->
                    externalHandler.handle(decision.scheme, decision.target)
                is NavigationDecision.Block ->
                    recordBlockedNavigation(decision.rawUrl ?: rawUrl, decision.reason, NavigationType.JS)
                is NavigationDecision.Allow -> Unit // about:blank/blob — لا شيء
            }
        }
    }

    /** روابط مفتوحة من السجل/الخارج عندما يكون النشاط موجودًا مسبقًا (singleTask). */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val url = intent.getStringExtra(EXTRA_OPEN_URL)
        if (!url.isNullOrBlank()) navigateFromExternal(url, newTab = false)
    }

    override fun onDestroy() {
        // تفكيك جميع WebViews لمنع التسريب
        tabsManager.destroyAll()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_OPEN_URL = "extra_open_url"
        private const val SUBFRAME_EXTERNAL_DEBOUNCE_MS = 2000L

        /** v1.8.0 — أبعاد شرائط استبعاد إيماءة النظام (dp). */
        private const val GESTURE_EXCLUSION_WIDTH_DP = 32
        private const val GESTURE_EXCLUSION_MAX_HEIGHT_DP = 200
    }
}
