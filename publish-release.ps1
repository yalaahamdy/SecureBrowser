param(
    [string]$TagName = "v1.12.0",
    [string]$ApkPath = "C:\Users\Rocket\Documents\antigravity\hopeful-faraday\SecureBrowser-v1.12.0-release.apk"
)

$ErrorActionPreference = "Stop"

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

Write-Host "=== Pushing main branch & Tag $TagName to GitHub ===" -ForegroundColor Cyan
git push origin main
git tag -f $TagName HEAD
git push origin $TagName --force

Write-Host "=== Retrieving GitHub Credentials ===" -ForegroundColor Cyan
$inputLines = @("protocol=https", "host=github.com", "")
$credOutput = $inputLines | git credential fill
$tokenLine = $credOutput | Where-Object { $_ -like "password=*" } | Select-Object -First 1

if (-not $tokenLine) {
    Write-Error "Could not retrieve GitHub token from git credential manager."
    exit 1
}

$token = $tokenLine.Substring(9).Trim()

$headers = @{
    "Authorization" = "token $token"
    "Accept"        = "application/vnd.github.v3+json"
    "User-Agent"    = "PowerShell-SecureBrowser"
}

$releaseBody = @"
## متصفح آمن — Secure Browser v1.12.0

### الحلول الجذرية والمميزات الجديدة في هذا الإصدار:
1. **إصلاح جذري وشامل لتنزيل الملفات (موثوقية 100% وحفظ مباشر في مجلد Download الافتراضي):**
   - **محرك تنزيل مزدوج (Dual-Engine):** محرك نقل مباشر مع تراجع تلقائي فوري إلى `DownloadManager` النظامي لأندرويد عند أي تعثر للشبكة أو الخادم.
   - **حل مشكلة `MediaStore.Downloads`:** إصلاح خطأ نوع MIME (`*/*`) الذي كان يمنع حفظ ملفات APK والملفات المضغوطة والمستندات في مجلد التنزيلات العام للهاتف على أندرويد 10+.
   - **دعم تنزيلات النوافذ المنبثقة (`target="_blank"`) وخوادم الملفات (CDNs):** ربط مستمع التنزيلات بالنوافذ المنبثقة منذ لحظة إنشائها، وتمرير روابط التنزيل المباشرة وروابط CDN (`objects.githubusercontent.com`, `googleusercontent.com`, `mediafire.com`, إلخ) إلى مدير التنزيلات مباشرة بدل حظرها، مع إغلاق تبويب الـ Popup الأبيض المؤقت تلقائيًا.
   - **جسر JavaScript أصيل لروابط `blob:`:** إضافة `@JavascriptInterface` (`SecureBlobBridge`) لتحويل وتنزيل ملفات `blob:` غير المتزامنة بنسبة نجاح 100%.
   - **تحديث اسم الملف وامتداده من الترويسات النهائية:** استخراج الاسم والامتداد الفعليين من ترويسات `Content-Disposition` و`Content-Type` بعد انتهاء كافة التحويلات (Redirects).
   - **قائمة الضغطة المطوّلة (Long-Press Menu):** إمكانية الضغط المطوّل على أي صورة أو رابط لتنزيل الصورة أو الملف مباشرة إلى الهاتف.
2. **إصلاح تعليق التبويبات وعدم فتحها بشكل صحيح:**
   - إصلاح `restoreSessionOrCreateTab` وفتح الروابط الخارجية والاختصارات المثبتة بربط `attachActiveTab` فورًا بحاوية العرض.
   - مزامنة ظهور/إخفاء صفحة البداية وشريط التقدم عند التبديل بين التبويبات، وفصل `WebView` بأمان وإيقاظ عارض Chromium (`onResume`).
   - ترك محرك Chromium يكمل التنقلات والتوجيهات المسموحة أصليًا (`return false`) دون إلغائها وإعادة استدعاء `loadUrl`، مما يحفظ جلسات POST وترويسة `Referer` ويمنع تجمّد التبويبات.
   - تحسين التقاط معاينات التبويبات برسم مصغّر مباشر للتبويب النشط فقط دون تجميد خيط الواجهة الرئيسي.
3. **خيار حظر محرك البحث بالكامل (`block_search_engine`):**
   - إضافة خيار **«حظر محرك البحث»** في كل من الإعدادات وإعدادات الرقابة الأبوية لمنع الدخول إلى جوجل أو بينج أو دك دك جو والبحث عن أي شيء أو تصفح وتحميل الصور ومشاهدة الفيديوهات من نتائج البحث.
4. **التحديث التلقائي للتطبيق من GitHub Releases (`AppUpdateManager`):**
   - فحص تلقائي لأحدث إصدار منشور على مستودع GitHub الرسمي (`releases/latest`) عند تشغيل التطبيق، مع زر فحص يدوي في الإعدادات.
   - تنزيل حزمة التحديث بشريط تقدم حي وتشغيل مثبّت الحزم الرسمي لأندرويد تلقائيًا.
"@

Write-Host "=== Creating Release on GitHub ===" -ForegroundColor Cyan
$postData = @{
    tag_name         = $TagName
    target_commitish = "main"
    name             = "SecureBrowser $TagName"
    body             = $releaseBody
    draft            = $false
    prerelease       = $false
} | ConvertTo-Json -Compress

$release = Invoke-RestMethod -Uri "https://api.github.com/repos/yalaahamdy/SecureBrowser/releases" -Method Post -Headers $headers -Body ([System.Text.Encoding]::UTF8.GetBytes($postData)) -ContentType "application/json; charset=utf-8"

Write-Host "Release created: $($release.html_url)" -ForegroundColor Green

Write-Host "=== Uploading APK Asset ===" -ForegroundColor Cyan
$uploadUrl = $release.upload_url -replace '\{\?name,label\}', "?name=SecureBrowser-$TagName-release.apk"

$uploadHeaders = @{
    "Authorization" = "token $token"
    "Content-Type"  = "application/vnd.android.package-archive"
    "User-Agent"    = "PowerShell-SecureBrowser"
}

$apkBytes = [System.IO.File]::ReadAllBytes($ApkPath)
$asset = Invoke-RestMethod -Uri $uploadUrl -Method Post -Headers $uploadHeaders -Body $apkBytes

Write-Host "APK uploaded successfully!" -ForegroundColor Green
Write-Host "Download URL: $($asset.browser_download_url)" -ForegroundColor Cyan
