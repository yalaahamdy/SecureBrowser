package com.securebrowser.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * محمّل أيقونات المواقع (Favicons) — v1.7.0
 *
 * يغذي صفحة البداية وقائمة المواقع المسموحة والسجل والتنزيلات بأيقونة
 * حقيقية لكل نطاق بدل الحرف البديل.
 *
 * طبقات التحميل: الذاكرة (LruCache) → القرص (cacheDir/favicons) → الشبكة
 * بثلاثة مصادر احتياطية (DuckDuckGo → Google S2 → /favicon.ico المباشر).
 * لا يعمل إلا عبر http(s) بمهلات قصيرة حتى لا يعلّق أي شاشة.
 *
 * [bind] آمنة لإعادة استخدام الـ ViewHolder: علامة tag على الـ ImageView
 * تمنع تطبيق نتيجة موقع قديم على صف أعيد استخدامه لموقع آخر.
 */
object FaviconLoader {

    private const val CONNECT_TIMEOUT_MS = 5000
    private const val READ_TIMEOUT_MS = 8000

    /** ذاكرة مؤقتة بحجم البايتات — سقف 8MB كي لا تنافس معاينات التبويبات. */
    private val memoryCache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 16).toInt().coerceAtMost(8 * 1024 * 1024)
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private var diskDirRef: File? = null

    private fun diskDir(context: Context): File =
        diskDirRef ?: File(context.applicationContext.cacheDir, "favicons")
            .apply { mkdirs() }
            .also { diskDirRef = it }

    /** الحرف البديل لمضيف (يُستخدم حتى تنجح الأيقونة أو إن فشلت). */
    fun initialFor(host: String?): String =
        host?.removePrefix("www.")?.trim()?.firstOrNull()?.uppercaseChar()?.toString() ?: "?"

    /** يحمّل أيقونة المضيف: الذاكرة → القرص → الشبكة. لا يرمي استثناءات أبدًا. */
    suspend fun load(context: Context, host: String?): Bitmap? {
        val key = host?.trim()?.lowercase().orEmpty()
        if (key.isEmpty() || !key.contains('.')) return null
        memoryCache.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            val file = File(diskDir(context), key.replace(Regex("[^a-zA-Z0-9._-]"), "_") + ".png")
            if (file.isFile) {
                BitmapFactory.decodeFile(file.absolutePath)?.let {
                    memoryCache.put(key, it)
                    return@withContext it
                }
                file.delete() // ملف تالف — أعد الجلب
            }
            val fetched = fetch(key)
            if (fetched != null) {
                runCatching {
                    file.outputStream().use { out ->
                        fetched.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                }
                memoryCache.put(key, fetched)
            }
            fetched
        }
    }

    /** ثلاثة مصادر بالترتيب — الأول الذي يعيد صورة صالحة يكفي. */
    private fun fetch(host: String): Bitmap? {
        val sources = listOf(
            "https://icons.duckduckgo.com/ip3/$host.ico",
            "https://www.google.com/s2/favicons?domain=$host&sz=64",
            "https://$host/favicon.ico"
        )
        for (src in sources) {
            runCatching { fetchUrl(src) }.getOrNull()?.let { return it }
        }
        return null
    }

    private fun fetchUrl(url: String): Bitmap? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                )
            }
            if (conn.responseCode !in 200..299) null
            else conn.inputStream.use { stream -> BitmapFactory.decodeStream(stream) }
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * ربط أيقونة بصف قائمة مع حرف بديل يظل خلفها حتى تنجح الصورة.
     * آمن مع إعادة تدوير الصفوف عبر tag — النتيجة القديمة تُهمل.
     */
    fun bind(
        hostView: View,
        icon: ImageView,
        letter: TextView?,
        host: String?,
        fallbackLetter: String? = null
    ) {
        val owner = hostView.context as? LifecycleOwner ?: return
        val token = host?.trim()?.lowercase().orEmpty()
        icon.tag = token
        icon.isVisible = false
        letter?.isVisible = true
        letter?.text = fallbackLetter ?: initialFor(host)
        if (token.isEmpty()) return
        owner.lifecycleScope.launch {
            val bmp = load(hostView.context, host)
            if (icon.tag != token) return@launch // الصف أُعيد استخدامه لموقع آخر
            if (bmp != null) {
                icon.isVisible = true
                icon.setImageBitmap(bmp)
                letter?.isVisible = false
            }
        }
    }
}
