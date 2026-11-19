param(
    [string]$TagName = "v1.14.0",
    [string]$ApkPath = "C:\Users\Rocket\Documents\antigravity\hopeful-faraday\SecureBrowser-v1.14.0-release.apk"
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
## متصفح آمن — Secure Browser v1.14.0

### تحسينات الستايلات والأزرار وإصلاح المشاكل الحرجة:
1. **إكمال وتوحيد الستايلات والأزرار والثيمات عبر التطبيق:**
   - دعم شريط التنقل السفلي للنظام (`windowLightNavigationBar`) للوضعين النهاري والليلي (`values-v27` و `values-night-v27`) عبر `Base.Theme.SecureBrowser`.
   - تحديث `bg_section_card`، و`sheet_site_info`، و`item_blocked_row`، و`item_recent_row` (شارات ملوّنة للخط الزمني)، و`dialog_backup_export` و`dialog_backup_import` (`MaterialCheckBox`/`MaterialRadioButton`)، و`view_error`.
   - توحيد أزرار الأشرطة العلوية ومقابض السحب (`bg_drag_handle`) في جميع قوائم `BottomSheet`، وحماية شاشة إنشاء رمز الوالدين بالتمرير على الشاشات الصغيرة، وضبط مفاتيح `PinPadView`.
   - إصلاح إخفاء رسالة السجل الفارغ في السجل السريع.
2. **إصلاح مشاكل حرجة في التحميلات والتبويبات:**
   - إظهار مؤشر التحميل أثناء فحص الروابط المحوّلة (`probeUrlForDownload`) في المتصفح وإطفاؤه فور بدء التنزيل، وإغلاق التبويبات المنبثقة العابرة تلقائيًا.
   - إصلاح إعادة المحاولة (`retry`) بحذف بقايا الملف التالف وتصفير التقدم مع إبقاء الرابط الكامل غير مقصوص، وحماية `delete` بإلغاء أي تدفق `blob:` نشط.
   - إضافة فتح ومشاركة احتياطيين عبر الاستعلام من `MediaStore.Downloads` على Android 10+، وحساب السرعة الحية والـ ETA وإشعارات التقدم أيضًا عند عمل `DownloadManager` النظامي الاحتياطي.
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
