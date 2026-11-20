param(
    [string]$TagName = "v1.15.0",
    [string]$ApkPath = "C:\Users\Rocket\Documents\antigravity\hopeful-faraday\SecureBrowser-v1.15.0-release.apk"
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
## متصفح آمن — Secure Browser v1.15.0

### ميزة جديدة كليًا: تصفح قنوات يوتيوب المحصّن (Hardened YouTube Channel Whitelist)
1. **إتاحة قنوات يوتيوب محددة وفيديوهاتها فقط بصرامة تامة (Fail-Closed):**
   - إمكانية تفعيل **وضع قنوات يوتيوب المسموحة** من لوحة الوالدين وإضافة القنوات بلصق أي رابط (رابط قناة، `@handle`، أو رابط فيديو منها) مع معاينة وتأكيد قبل الحفظ.
   - الاعتماد على معرّف القناة الثابت (`UC...`) في قاعدة البيانات (`Room v3`) وكاش محلي سريع للتحقق من كل فيديو قبل التشغيل.
   - تطبيق سياسة القنوات بأسبقية فوق القائمة البيضاء العامة (فلا تتجاوزها أي قاعدة عامة لـ `youtube.com`).
2. **حماية متعددة الطبقات ضد جميع محاولات التجاوز:**
   - سكربت `DocumentStart` + جسر `@JavascriptInterface` لاعتراض طلبات API يوتيوب (`player`, `reel_item_watch`, `browse`, `search`) داخل التنقل أحادي الصفحة (SPA).
   - اعتراض الشبكة (`shouldInterceptRequest`) لقفل البث المباشر `googlevideo.com/videoplayback` بدون فيديو مصرّح به، وحظر إطارات `/embed/` غير المسموحة حتى داخل المواقع التعليمية الأخرى.
   - تحصين واجهة يوتيوب بإخفاء البحث والمقترحات وشاشة النهاية والتشغيل التلقائي والتعليقات، وخيار حظر مقاطع Shorts حتى من القنوات المسموحة.
   - منع الهروب عبر تطبيق يوتيوب الرسمي (`vnd.youtube://` و `intent://`) عند تفعيل الوضع.
3. **تجربة استخدام سلسة ونسخ احتياطي متكامل:**
   - بطاقات القنوات المسموحة في صفحة البداية للوصول بنقرة واحدة، وزر «السماح بهذه القناة» المحمي بالرمز في شاشة الحظر.
   - تضمين القنوات المسموحة في تصدير واستيراد النسخ الاحتياطي بتوافق عكسي كامل.
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
