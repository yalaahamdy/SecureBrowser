package com.securebrowser.app.browser

import android.webkit.WebView
import com.securebrowser.app.di.ServiceLocator
import org.json.JSONArray
import org.json.JSONObject

/**
 * مدير جلسات التصفح والتبويبات — مرحلة 2 (§5).
 *
 * يوفر: إنشاء/إغلاق/تبديل التبويبات + عداد + معاينات + استعادة الجلسة.
 *
 * **الضمانة الأمنية:** كل تبويب جديد (يدوي أو منبثق) يملك WebView عبر المصنع
 * المؤمَّن نفسه — أي تنقل داخله يمر عبر SecurityEngine مثل أي تبويب آخر.
 * لا يوجد أي مسار هنا يتجاوز محرك الأمان.
 */
class TabsManager(private val factory: (Long) -> WebView) {

    private val tabList = mutableListOf<TabSession>()

    val tabs: List<TabSession> get() = tabList.toList()

    var activeTab: TabSession? = null
        private set

    /**
     * مستمع رئيسي للتغييرات (شريحة عدّاد التبويبات في النشاط).
     * v1.5.0: أُضيفت قائمة مستمعين إضافيين ([addOnTabsChangedListener])
     * كي تستمع لوحة التبويبات المفتوحة كذلك حيًّا — كانت اللوحة تلتقط لقطة
     * ثابتة عند الفتح فلا تعكس إغلاق/إضافة تبويب إلا بإغلاقها وإعادة فتحها.
     */
    var onTabsChanged: (() -> Unit)? = null

    private val extraListeners = mutableListOf<() -> Unit>()

    /** يضيف مستمعًا حيًّا للتغييرات ويعيد مرجعًا لإزالته لاحقًا. */
    fun addOnTabsChangedListener(listener: () -> Unit): () -> Unit {
        if (listener !in extraListeners) extraListeners.add(listener)
        return listener
    }

    fun removeOnTabsChangedListener(listener: () -> Unit) {
        extraListeners.remove(listener)
    }

    private fun fireTabsChanged() {
        onTabsChanged?.invoke()
        // نسخة دفاعية: قد يُضيف مستمع أثناء الإشعار نفسه
        extraListeners.toList().forEach { try { it() } catch (e: Exception) { extraListeners.remove(it) } }
    }

    fun createTab(activate: Boolean = true, restoreUrl: String? = null): TabSession? {
        if (tabList.size >= MAX_TABS) return null
        val id = nextId()
        val session = TabSession(id, factory(id))
        session.pendingRestoreUrl = restoreUrl
        tabList.add(session)
        if (activate) activeTab = session
        fireTabsChanged()
        saveSession()
        return session
    }

    /**
     * إنشاء تبويب يتبنى WebView **قائمًا** (v1.4.0) — نوافذ popup التي
     * تُعتمد عبر PopupWindowShell بدل إعادة إنشاء WebView جديد (فقدان
     * POST ومرجع window الحي). العميلان الحقيقيان يُربطان في المتصل
     * بمعرف التبويب الجديد قبل أي تنقل لاحق.
     */
    fun createTabWithWebView(webView: WebView, activate: Boolean = true): TabSession? {
        if (tabList.size >= MAX_TABS) return null
        val id = nextId()
        val session = TabSession(id, webView)
        tabList.add(session)
        if (activate) activeTab = session
        fireTabsChanged()
        saveSession()
        return session
    }

    fun closeTab(id: Long) {
        val index = tabList.indexOfFirst { it.id == id }
        if (index < 0) return
        val closing = tabList.removeAt(index)
        closing.webView.stopLoading()
        closing.releasePreview()
        closing.webView.destroy()

        if (closing.id == activeTab?.id) {
            activeTab = when {
                tabList.isNotEmpty() -> tabList[(index - 1).coerceAtLeast(0)]
                else -> null
            }
            if (activeTab == null && tabList.isEmpty()) {
                createTab(activate = true)
            }
        }
        fireTabsChanged()
        saveSession()
    }

    fun switchTo(id: Long) {
        val target = tabList.firstOrNull { it.id == id } ?: return
        // التقاط معاينة التبويب المغادر قبل التبديل
        activeTab?.capturePreview()
        activeTab = target
        fireTabsChanged()
        saveSession()
    }

    fun find(id: Long): TabSession? = tabList.firstOrNull { it.id == id }

    fun activeWebView(): WebView? = activeTab?.webView

    // ————————————————— استعادة الجلسة (§5 Session restore) —————————————————

    /** مواصفات التبويبات المخزنة من الجلسة السابقة. */
    data class TabRestoreSpec(val url: String?, val title: String?, val isActive: Boolean)

    /** يُستدعى مرة عند بدء النشاط قبل إنشاء التبويبات. */
    fun loadSessionSpecs(): List<TabRestoreSpec>? {
        if (!ServiceLocator.settingsRepository.restoreSession) return null
        val raw = ServiceLocator.secureStorage.getString(KEY_SESSION) ?: return null
        return try {
            val obj = JSONObject(raw)
            val arr = obj.optJSONArray("tabs") ?: return null
            val activeIndex = obj.optInt("active", 0)
            val specs = mutableListOf<TabRestoreSpec>()
            for (i in 0 until arr.length()) {
                val t = arr.optJSONObject(i) ?: continue
                specs.add(
                    TabRestoreSpec(
                        url = t.optString("url", "").takeIf { it.isNotBlank() },
                        title = t.optString("title", "").takeIf { it.isNotBlank() },
                        isActive = i == activeIndex
                    )
                )
            }
            specs.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
    }

    fun clearPersistedSession() {
        ServiceLocator.secureStorage.remove(KEY_SESSION)
    }

    /** حفظ الجلسة الحالية (روابط وحالات — بلا محتوى حساس). */
    fun saveSession() {
        try {
            if (!ServiceLocator.settingsRepository.restoreSession) return
            val arr = JSONArray()
            var activeIndex = 0
            tabList.forEachIndexed { index, tab ->
                val entry = JSONObject()
                val url = tab.uiState.url ?: tab.pendingRestoreUrl
                entry.put("url", url ?: "")
                entry.put("title", tab.uiState.title ?: "")
                if (tab == activeTab) activeIndex = index
                arr.put(entry)
            }
            val obj = JSONObject()
            obj.put("tabs", arr)
            obj.put("active", activeIndex)
            ServiceLocator.secureStorage.putString(KEY_SESSION, obj.toString())
        } catch (e: Exception) {
            // حفظ الجلسة اختياري — لا يوقف التصفح
        }
    }

    /** تفكيك كل التبويبات (عند تدمير النشاط). */
    fun destroyAll() {
        for (tab in tabList) {
            tab.webView.stopLoading()
            tab.releasePreview()
            tab.webView.destroy()
        }
        tabList.clear()
        activeTab = null
    }

    private var nextIdCounter = 0L
    private fun nextId(): Long = ++nextIdCounter

    companion object {
        const val MAX_TABS = 20
        private const val KEY_SESSION = "session_tabs"
    }
}
