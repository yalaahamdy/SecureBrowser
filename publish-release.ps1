param(
    [string]$TagName = "v1.13.0",
    [string]$ApkPath = "C:\Users\Rocket\Documents\antigravity\hopeful-faraday\SecureBrowser-v1.13.0-release.apk"
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
## متصفح آمن — Secure Browser v1.13.0

### إعادة الهيكلة الشاملة (مدير التنزيلات + الستايلات والأزرار + محرك التحميل):
1. **إعادة هيكلة واجهة مدير التنزيلات من الصفر (`DownloadsActivity` & `item_download_row`):**
   - **واجهة احترافية متكاملة:** شريط علوي بإضافة تنزيل من رابط مباشر (`+`) ومسح السجلات المنتهية، وبطاقة إحصائيات عليا (عدد الملفات، الحجم الكلي، النشطة الآن، ومسار الحفظ الدائم `Download`).
   - **بحث فوري و 8 تصنيفات فلترة:** الكل، الجارية، المستندات وPDF، الصور، الفيديو، الصوتيات، تطبيقات APK، والملفات المضغوطة.
   - **بطاقات حية بلا ارتعاش (`ListAdapter` + `DiffUtil`):** عرض نسبة التقدم والسرعة الحية (`MB/s`/`KB/s`) والوقت المتبقي (`ETA`) مع أزرار إجراءات سريعة مباشرة على البطاقة (إيقاف/استئناف/إعادة محاولة/فتح) وقائمة خيارات شاملة تتضمن إعادة تسمية الملف ونسخ الرابط.
   - **إشعارات نظام حية وشريط Snackbar:** ظهور تقدم التنزيل والسرعة في إشعارات النظام وSnackbar داخل المتصفح مع زر «عرض التنزيلات».
2. **إصلاح جذري لمشاكل فشل التحميلات (`BrowserDownloadManager` & `SecureWebViewFactory` & `BrowserActivity`):**
   - **كشف الملفات المخفية خلف Redirects / APIs (`probeUrlForDownload`):** فحص سريع لترويسات الروابط المحولة داخل الصفحات المسموحة؛ إذا أعاد الخادم `Content-Disposition: attachment` أو ملفًا ثنائيًا يبدأ التنزيل فورًا بدل شاشة الحظر.
   - **حفظ الروابط الطويلة (>2048 حرفًا) وتجميع `Set-Cookie` أثناء الـ Redirects:** منع إفساد تواقيع S3/Drive/CDN ومنع فقدان كوكيز الجلسات بين التحويلات.
   - **بث مقسم لروابط `blob:` (`Chunked Streaming`):** قراءة ملفات `blob:` على دفعات `256 KB` إلى القرص مباشرة لتجنب نفاد الذاكرة أو فشل الملفات الكبيرة.
   - **محرك متعدد الاستراتيجيات:** محاولة ثانية تلقائية بدون `Referer` وبلا `Range` مع كشف صفحات الخطأ HTML المزيفة، والانتقال الآمن إلى `DownloadManager` النظامي وحفظ النسخ المؤكدة في مجلد `Download` بالهاتف.
3. **إكمال الستايلات والثيمات والأزرار في التطبيق بالكامل:**
   - استكمال كامل ألوان وسمات Material 3 للوضعين النهاري والليلي (`colors.xml`, `values-night/colors.xml`, `themes.xml`).
   - تعريف أنماط أزرار موحدة (`Primary`, `Tonal`, `Outlined`, `Text`, `Danger`, `Compact`, `IconButton`), وحقول إدخال ورقاقات وحوارات دائرية موحدة (`MaterialAlertDialogBuilder` و `BottomSheet`).
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
