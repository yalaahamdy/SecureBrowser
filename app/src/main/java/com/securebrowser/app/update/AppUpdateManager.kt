package com.securebrowser.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view. Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.securebrowser.app.R
import com.securebrowser.app.di.ServiceLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * مدير التحديثات التلقائية من مستودع GitHub الرسمي (v1.12.0).
 *
 * يفحص أحدث إصدار منشور عبر GitHub Releases API:
 * `https://api.github.com/repos/yalaahamdy/SecureBrowser/releases/latest`
 * ويقارن رقم الإصدار الدلالي بالإصدار المثبّت، وعند توفر إصدار أحدث يعرض حوار التحديث،
 * ويحمّل حزمة APK بشريط تقدم حي، ثم يطلق مثبّت الحزم الرسمي لأندرويد عبر FileProvider.
 */
object AppUpdateManager {

    private const val GITHUB_LATEST_RELEASE_API =
        "https://api.github.com/repos/yalaahamdy/SecureBrowser/releases/latest"

    @Volatile
    private var autoCheckedThisSession = false

    @Volatile
    private var pendingInstallApk: File? = null

    data class ReleaseInfo(
        val tagName: String,
        val version: String,
        val title: String,
        val notes: String,
        val apkDownloadUrl: String,
        val apkFileName: String,
        val apkSizeBytes: Long
    )

    fun currentVersionName(context: Context): String {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName?.removePrefix("v")?.removePrefix("V")?.trim().orEmpty().ifBlank { "1.0.0" }
        } catch (_: Exception) {
            "1.0.0"
        }
    }

    /**
     * يُستدعى عند عودة النشاط للواجهة (onResume): إذا كان المستخدم قد منح للتو
     * صلاحية تثبيت الحزم من هذا المصدر، يكتمل التثبيت تلقائيًا دون الحاجة لإعادة التنزيل.
     */
    fun onActivityResume(activity: AppCompatActivity) {
        val pending = pendingInstallApk ?: return
        if (!pending.exists()) {
            pendingInstallApk = null
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            activity.packageManager.canRequestPackageInstalls()
        ) {
            pendingInstallApk = null
            launchPackageInstaller(activity, pending)
        }
    }

    /**
     * فحص التحديثات:
     * @param manual إذا كان `false` يُفحص تلقائيًا مرة واحدة في الجلسة بصمت عند عدم وجود تحديث؛
     *               وإذا كان `true` يعرض إشعارًا للمستخدم بحالة الفحص.
     */
    fun checkForUpdates(activity: AppCompatActivity, manual: Boolean = false) {
        if (!manual) {
            if (autoCheckedThisSession) return
            autoCheckedThisSession = true
        } else {
            Toast.makeText(activity, R.string.update_checking, Toast.LENGTH_SHORT).show()
        }

        val installedVersion = currentVersionName(activity)
        activity.lifecycleScope.launch {
            val release = withContext(Dispatchers.IO) {
                fetchLatestRelease()
            }
            if (activity.isFinishing || activity.isDestroyed) return@launch

            if (release == null) {
                if (manual) {
                    Toast.makeText(activity, R.string.update_check_failed, Toast.LENGTH_LONG).show()
                }
                return@launch
            }

            if (isNewerVersion(release.version, installedVersion)) {
                showUpdateAvailableDialog(activity, release)
            } else if (manual) {
                Toast.makeText(
                    activity,
                    activity.getString(R.string.update_already_latest, installedVersion),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun fetchLatestRelease(): ReleaseInfo? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(GITHUB_LATEST_RELEASE_API)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 15_000
                useCaches = false
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "SecureBrowser-Android-Updater")
            }
            val code = conn.responseCode
            if (code !in 200..299) return null

            val bodyText = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val json = JSONObject(bodyText)
            val tagName = json.optString("tag_name", "").trim()
            if (tagName.isBlank()) return null

            val cleanVersion = tagName.removePrefix("v").removePrefix("V").trim()
            val title = json.optString("name", tagName).trim().ifBlank { tagName }
            val notes = json.optString("body", "").trim()

            val assets = json.optJSONArray("assets") ?: return null
            var apkUrl: String? = null
            var apkName: String? = null
            var apkSize = -1L

            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val name = asset.optString("name", "")
                val dlUrl = asset.optString("browser_download_url", "")
                if (name.endsWith(".apk", ignoreCase = true) && dlUrl.startsWith("https://", ignoreCase = true)) {
                    apkUrl = dlUrl
                    apkName = name
                    apkSize = asset.optLong("size", -1L)
                    break
                }
            }

            if (apkUrl.isNullOrBlank()) return null
            ReleaseInfo(
                tagName = tagName,
                version = cleanVersion,
                title = title,
                notes = notes,
                apkDownloadUrl = apkUrl,
                apkFileName = apkName ?: "SecureBrowser-$tagName.apk",
                apkSizeBytes = apkSize
            )
        } catch (_: Exception) {
            null
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * مقارنة دلالية لأرقام الإصدارات (مثل 1.12.0 > 1.11.0).
     */
    fun isNewerVersion(latest: String, current: String): Boolean {
        val latestParts = parseVersionParts(latest)
        val currentParts = parseVersionParts(current)
        val maxLen = maxOf(latestParts.size, currentParts.size)
        for (i in 0 until maxLen) {
            val l = latestParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (l > c) return true
            if (l < c) return false
        }
        return false
    }

    private fun parseVersionParts(ver: String): List<Int> {
        return ver.trim()
            .removePrefix("v")
            .removePrefix("V")
            .substringBefore('-')
            .split('.')
            .map { it.filter { ch -> ch.isDigit() }.toIntOrNull() ?: 0 }
    }

    private fun showUpdateAvailableDialog(activity: AppCompatActivity, release: ReleaseInfo) {
        val notesText = release.notes.ifBlank {
            activity.getString(R.string.update_available_default_notes)
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.update_available_title, "v${release.version}"))
            .setMessage(notesText)
            .setCancelable(true)
            .setPositiveButton(R.string.update_now) { _, _ ->
                startUpdateDownload(activity, release)
            }
            .setNegativeButton(R.string.update_later, null)
            .show()
    }

    private fun startUpdateDownload(activity: AppCompatActivity, release: ReleaseInfo) {
        val pad = (22 * activity.resources.displayMetrics.density).toInt()
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad / 2)
        }
        val progressBar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 100
            progress = 0
        }
        val statusText = TextView(activity).apply {
            text = "0%"
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, pad / 2, 0, 0)
        }
        container.addView(progressBar)
        container.addView(statusText)

        var downloadJob: Job? = null
        val progressDialog = MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.update_downloading_title, "v${release.version}"))
            .setView(container)
            .setCancelable(false)
            .setNegativeButton(R.string.cancel) { dialog, _ ->
                downloadJob?.cancel()
                dialog.dismiss()
            }
            .create()

        progressDialog.show()

        downloadJob = activity.lifecycleScope.launch {
            val downloadedFile = withContext(Dispatchers.IO) {
                downloadApkToFile(activity, release) { downloadedBytes, totalBytes ->
                    val pct = if (totalBytes > 0) ((downloadedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100) else 0
                    val dlMb = formatMegabytes(downloadedBytes)
                    val totMb = if (totalBytes > 0) formatMegabytes(totalBytes) else "?"
                    activity.runOnUiThread {
                        if (progressDialog.isShowing) {
                            progressBar.isIndeterminate = totalBytes <= 0
                            progressBar.progress = pct
                            statusText.text = activity.getString(
                                R.string.update_downloading_progress,
                                pct,
                                dlMb,
                                totMb
                            )
                        }
                    }
                }
            }

            if (progressDialog.isShowing) {
                progressDialog.dismiss()
            }
            if (activity.isFinishing || activity.isDestroyed) return@launch

            if (downloadedFile != null && downloadedFile.exists() && downloadedFile.length() > 100_000L) {
                installApk(activity, downloadedFile)
            } else {
                Toast.makeText(activity, R.string.update_download_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    private suspend fun downloadApkToFile(
        context: Context,
        release: ReleaseInfo,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ): File? = withContext(Dispatchers.IO) {
        val baseCache = context.externalCacheDir ?: context.cacheDir
        val updatesDir = File(baseCache, "updates").apply { mkdirs() }
        val safeName = release.apkFileName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val targetFile = File(updatesDir, safeName)
        if (targetFile.exists()) targetFile.delete()

        var currentUrl = release.apkDownloadUrl
        var redirects = 0
        var conn: HttpURLConnection? = null

        try {
            while (redirects < 15) {
                val u = URL(currentUrl)
                val c = (u.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 25_000
                    readTimeout = 45_000
                    instanceFollowRedirects = false
                    useCaches = false
                    setRequestProperty("User-Agent", "SecureBrowser-Android-Updater")
                    setRequestProperty("Accept", "*/*")
                }
                c.connect()
                val code = c.responseCode
                if (code in 301..303 || code == 307 || code == 308) {
                    val loc = c.getHeaderField("Location")
                    c.disconnect()
                    if (loc.isNullOrBlank()) return@withContext null
                    currentUrl = URL(u, loc).toString()
                    redirects++
                    continue
                }
                if (code !in 200..299) {
                    c.disconnect()
                    return@withContext null
                }
                conn = c
                break
            }

            val activeConn = conn ?: return@withContext null
            val total = activeConn.contentLengthLong.takeIf { it > 0 } ?: release.apkSizeBytes

            activeConn.inputStream.use { input ->
                FileOutputStream(targetFile).use { output ->
                    val buf = ByteArray(64 * 1024)
                    var downloaded = 0L
                    var lastReport = 0L
                    while (isActive) {
                        val read = input.read(buf)
                        if (read == -1) break
                        output.write(buf, 0, read)
                        downloaded += read
                        val now = System.currentTimeMillis()
                        if (now - lastReport >= 150L) {
                            onProgress(downloaded, total)
                            lastReport = now
                        }
                    }
                    output.flush()
                    onProgress(downloaded, total)
                }
            }

            // تحقق سريع من ترويسة ملف APK (ZIP PK\x03\x04)
            if (!isValidApkFile(targetFile)) {
                targetFile.delete()
                return@withContext null
            }

            // حفظ نسخة أيضًا في مجلد Download العام للهاتف لسهولة الرجوع إليها
            try {
                ServiceLocator.downloadManager.publishToPublicDownloads(
                    context,
                    targetFile,
                    "application/vnd.android.package-archive"
                )
            } catch (_: Exception) {
            }

            targetFile
        } catch (_: Exception) {
            targetFile.delete()
            null
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    private fun isValidApkFile(file: File): Boolean {
        if (!file.exists() || file.length() < 100_000L) return false
        return try {
            val header = ByteArray(4)
            val read = file.inputStream().use { it.read(header) }
            read == 4 && header[0] == 0x50.toByte() && header[1] == 0x4B.toByte()
        } catch (_: Exception) {
            false
        }
    }

    private fun installApk(activity: AppCompatActivity, apkFile: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activity.packageManager.canRequestPackageInstalls()
        ) {
            pendingInstallApk = apkFile
            AlertDialog.Builder(activity)
                .setTitle(R.string.update_install_permission_title)
                .setMessage(R.string.update_install_permission_msg)
                .setPositiveButton(R.string.update_grant_permission) { _, _ ->
                    try {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:${activity.packageName}")
                        )
                        activity.startActivity(intent)
                    } catch (_: Exception) {
                        launchPackageInstaller(activity, apkFile)
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }
        launchPackageInstaller(activity, apkFile)
    }

    private fun launchPackageInstaller(activity: AppCompatActivity, apkFile: File) {
        try {
            val uri = FileProvider.getUriForFile(
                activity,
                "${activity.packageName}.fileprovider",
                apkFile
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
            }
            activity.startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(activity, R.string.update_download_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun formatMegabytes(bytes: Long): String {
        val mb = bytes.toDouble() / (1024.0 * 1024.0)
        return String.format(Locale.US, "%.1f MB", mb)
    }
}
